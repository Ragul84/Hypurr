//! Voice chat on Linux: talk to a bot and hear its replies, all on this computer.
//! Recording: `pw-record` or `arecord`. Speech to text: whisper.cpp's `whisper-cli` with a local
//! model (`$HYPURR_WHISPER_MODEL`, or `~/.local/share/hypurr/whisper/ggml-base.en.bin`).
//! Reading replies aloud: `spd-say` or `espeak-ng` (optional; the text still shows in the chat).

use crate::client;
use adw::prelude::*;
use serde_json::{Value, json};
use std::cell::RefCell;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::rc::Rc;
use std::time::Instant;

/// The local programs voice chat uses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tools {
    pub recorder: String,
    pub whisper: String,
    pub model: PathBuf,
    pub speaker: Option<String>,
}

fn on_path(name: &str) -> bool {
    std::env::var_os("PATH")
        .is_some_and(|p| std::env::split_paths(&p).any(|d| d.join(name).is_file()))
}

fn default_model() -> PathBuf {
    std::env::var_os("HYPURR_WHISPER_MODEL").map_or_else(
        || {
            dirs::data_dir()
                .unwrap_or_default()
                .join("hypurr/whisper/ggml-base.en.bin")
        },
        PathBuf::from,
    )
}

/// Picks tools from what is installed; `None` names what's missing.
pub fn pick(
    has: impl Fn(&str) -> bool,
    model: PathBuf,
    model_exists: bool,
) -> Result<Tools, String> {
    let recorder = ["pw-record", "arecord"].into_iter().find(|t| has(t));
    let whisper = ["whisper-cli", "whisper-cpp"].into_iter().find(|t| has(t));
    let speaker = ["spd-say", "espeak-ng"]
        .into_iter()
        .find(|t| has(t))
        .map(str::to_owned);
    match (recorder, whisper) {
        (None, _) => Err("Voice chat needs pw-record (PipeWire) or arecord (ALSA).".into()),
        (_, None) => Err("Voice chat needs whisper.cpp (whisper-cli) for speech to text.".into()),
        _ if !model_exists => Err(format!(
            "Voice chat needs a whisper model at {}.",
            model.display()
        )),
        (Some(r), Some(w)) => Ok(Tools {
            recorder: r.into(),
            whisper: w.into(),
            model,
            speaker,
        }),
    }
}

pub fn tools() -> Result<Tools, String> {
    let model = default_model();
    let exists = model.is_file();
    pick(on_path, model, exists)
}

/// Arguments to record 16 kHz mono WAV (what whisper wants) into `wav`.
pub fn record_args(recorder: &str, wav: &Path) -> Vec<String> {
    let wav = wav.display().to_string();
    if recorder == "arecord" {
        vec![
            "-q".into(),
            "-f".into(),
            "S16_LE".into(),
            "-r".into(),
            "16000".into(),
            "-c".into(),
            "1".into(),
            wav,
        ]
    } else {
        vec![
            "--rate".into(),
            "16000".into(),
            "--channels".into(),
            "1".into(),
            "--format".into(),
            "s16".into(),
            wav,
        ]
    }
}

/// whisper-cli prints the text; drop timestamps, blank lines and "[BLANK_AUDIO]"-style tags.
pub fn clean_transcript(out: &str) -> String {
    out.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !(l.starts_with('[') && l.ends_with(']')))
        .collect::<Vec<_>>()
        .join(" ")
}

/// Text to read aloud: markdown punctuation and code blocks removed.
pub fn spoken(markdown: &str) -> String {
    let mut out = String::new();
    let mut code = false;
    for line in markdown.lines() {
        if line.trim_start().starts_with("```") {
            code = !code;
            continue;
        }
        if code {
            continue;
        }
        let l: String = line
            .chars()
            .filter(|c| !matches!(c, '*' | '_' | '`' | '#' | '>'))
            .collect();
        if !l.trim().is_empty() {
            out.push_str(l.trim());
            out.push(' ');
        }
    }
    out.trim().to_owned()
}

struct Call {
    bot: String,
    tools: Tools,
    started: Instant,
    recording: Option<Child>,
    speaking: Option<Child>,
    wav: PathBuf,
    status: gtk::Label,
    talk: gtk::Button,
}

thread_local! {
    static CALL: RefCell<Option<Rc<RefCell<Call>>>> = const { RefCell::new(None) };
}

fn set_status(call: &Rc<RefCell<Call>>, text: &str) {
    call.borrow().status.set_label(text);
}

/// Opens voice chat with `bot`; explains what to install when the tools are missing.
pub fn open(parent: &adw::ApplicationWindow, bot: &str, name: &str) {
    let window = gtk::Window::builder()
        .transient_for(parent)
        .title(format!("Voice chat with {name}"))
        .default_width(360)
        .build();
    let body = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(12)
        .margin_top(18)
        .margin_bottom(18)
        .margin_start(18)
        .margin_end(18)
        .build();
    let status = gtk::Label::new(None);
    status.set_wrap(true);
    body.append(&status);
    let talk = gtk::Button::with_label("Talk");
    talk.add_css_class("suggested-action");
    let end = gtk::Button::with_label("End");
    end.add_css_class("destructive-action");
    let row = gtk::Box::builder()
        .spacing(8)
        .halign(gtk::Align::Center)
        .build();
    row.append(&talk);
    row.append(&end);
    body.append(&row);
    window.set_child(Some(&body));
    let tools = match tools() {
        Ok(t) => t,
        Err(e) => {
            status.set_label(&e);
            talk.set_sensitive(false);
            end.set_label("Close");
            let w = window.clone();
            end.connect_clicked(move |_| w.close());
            window.present();
            return;
        }
    };
    status.set_label("Press Talk, say what you want, then press Send.");
    let wav = std::env::temp_dir().join(format!("hypurr-voice-{}.wav", uuid::Uuid::new_v4()));
    let call = Rc::new(RefCell::new(Call {
        bot: bot.to_owned(),
        tools,
        started: Instant::now(),
        recording: None,
        speaking: None,
        wav,
        status,
        talk: talk.clone(),
    }));
    CALL.with(|c| *c.borrow_mut() = Some(call.clone()));
    let c2 = call.clone();
    talk.connect_clicked(move |_| toggle(&c2));
    let w = window.clone();
    end.connect_clicked(move |_| w.close());
    window.connect_close_request(|_| {
        finish();
        gtk::glib::Propagation::Proceed
    });
    window.present();
}

fn toggle(call: &Rc<RefCell<Call>>) {
    let recording = call.borrow_mut().recording.take();
    if let Some(mut child) = recording {
        // SIGINT so the recorder finishes the WAV header.
        let _ = Command::new("kill")
            .arg("-INT")
            .arg(child.id().to_string())
            .status();
        let _ = child.wait();
        call.borrow().talk.set_label("Talk");
        transcribe(call);
        return;
    }
    stop_speaking(call);
    let spawned = {
        let c = call.borrow();
        Command::new(&c.tools.recorder)
            .args(record_args(&c.tools.recorder, &c.wav))
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
    };
    match spawned {
        Ok(child) => {
            call.borrow_mut().recording = Some(child);
            call.borrow().talk.set_label("Send");
            set_status(call, "Listening…");
        }
        Err(e) => set_status(call, &format!("Couldn't use the microphone: {e}")),
    }
}

fn transcribe(call: &Rc<RefCell<Call>>) {
    set_status(call, "Transcribing…");
    call.borrow().talk.set_sensitive(false);
    let (whisper, model, wav) = {
        let c = call.borrow();
        (
            c.tools.whisper.clone(),
            c.tools.model.clone(),
            c.wav.clone(),
        )
    };
    let (tx, rx) = async_channel::bounded(1);
    std::thread::spawn(move || {
        let out = Command::new(whisper)
            .arg("-m")
            .arg(model)
            .arg("-f")
            .arg(&wav)
            .arg("-nt")
            .stderr(Stdio::null())
            .output();
        let _ = std::fs::remove_file(&wav);
        let _ =
            tx.send_blocking(out.map(|o| clean_transcript(&String::from_utf8_lossy(&o.stdout))));
    });
    let call = call.clone();
    gtk::glib::spawn_future_local(async move {
        let res = rx.recv().await.unwrap_or_else(|_| Ok(String::new()));
        call.borrow().talk.set_sensitive(true);
        match res {
            Ok(text) if !text.is_empty() => {
                set_status(&call, &format!("You: {text}\nWaiting for the reply…"));
                let bot = call.borrow().bot.clone();
                let c2 = call.clone();
                client::call("send", json!({"botId": bot, "text": text}), move |r| {
                    if let Err(e) = r {
                        set_status(&c2, &e);
                    }
                });
            }
            Ok(_) => set_status(&call, "Didn't catch that. Press Talk and try again."),
            Err(e) => set_status(&call, &format!("Speech to text failed: {e}")),
        }
    });
}

fn stop_speaking(call: &Rc<RefCell<Call>>) {
    if let Some(mut s) = call.borrow_mut().speaking.take() {
        let _ = s.kill();
        let _ = s.wait();
    }
}

/// A new chat entry: while a call with its bot is open, read the bot's final reply aloud.
pub fn on_entry(entry: &Value) {
    let Some(call) = CALL.with(|c| c.borrow().clone()) else {
        return;
    };
    if entry["botId"] != call.borrow().bot.as_str()
        || entry["kind"] != "agent"
        || entry["data"]["final"] != true
    {
        return;
    }
    let text = spoken(entry["data"]["text"].as_str().unwrap_or_default());
    if text.is_empty() {
        return;
    }
    set_status(&call, &format!("{text}\n\nPress Talk to answer."));
    stop_speaking(&call);
    let speaker = call.borrow().tools.speaker.clone();
    if let Some(speaker) = speaker {
        let mut cmd = Command::new(&speaker);
        if speaker == "spd-say" {
            cmd.arg("-w");
        }
        if let Ok(child) = cmd
            .arg(&text)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
        {
            call.borrow_mut().speaking = Some(child);
        }
    }
}

/// Ends the call: stops recording and speech, logs its length in the chat.
fn finish() {
    let Some(call) = CALL.with(|c| c.borrow_mut().take()) else {
        return;
    };
    if let Some(mut r) = call.borrow_mut().recording.take() {
        let _ = r.kill();
        let _ = r.wait();
    }
    stop_speaking(&call);
    let c = call.borrow();
    let _ = std::fs::remove_file(&c.wav);
    let secs = c.started.elapsed().as_secs();
    client::call("logCall", json!({"botId": c.bot, "seconds": secs}), |_| {});
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_installed_tools_or_names_the_missing_one() {
        let all = |t: &str| ["pw-record", "arecord", "whisper-cli", "spd-say"].contains(&t);
        let t = pick(all, "m.bin".into(), true).unwrap();
        assert_eq!(
            (t.recorder.as_str(), t.whisper.as_str()),
            ("pw-record", "whisper-cli")
        );
        assert_eq!(t.speaker.as_deref(), Some("spd-say"));
        assert!(
            pick(|t| t == "whisper-cli", "m".into(), true)
                .unwrap_err()
                .contains("arecord")
        );
        assert!(
            pick(|t| t == "arecord", "m".into(), true)
                .unwrap_err()
                .contains("whisper")
        );
        assert!(
            pick(all, "m.bin".into(), false)
                .unwrap_err()
                .contains("m.bin")
        );
        let quiet = pick(|t| t == "arecord" || t == "whisper-cpp", "m".into(), true).unwrap();
        assert_eq!(quiet.speaker, None);
    }

    #[test]
    fn records_16k_mono() {
        let a = record_args("arecord", Path::new("/tmp/x.wav"));
        assert!(a.contains(&"16000".to_owned()) && a.last().unwrap() == "/tmp/x.wav");
        let p = record_args("pw-record", Path::new("/tmp/x.wav"));
        assert!(p.contains(&"--channels".to_owned()));
    }

    #[test]
    fn cleans_transcripts_and_markdown() {
        assert_eq!(
            clean_transcript(" [BLANK_AUDIO]\n Fix the test.\n\n Then push.\n"),
            "Fix the test. Then push."
        );
        assert_eq!(
            spoken("**Done.**\n```\ncode\n```\n# Next: `npm test`"),
            "Done. Next: npm test"
        );
    }
}
