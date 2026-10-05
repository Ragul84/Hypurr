# Homebrew cask template for the Ragul84/homebrew-hypurr tap.
# The release job in .github/workflows/release-macos.yml fills in the version and the
# DMG's sha256 on every `v*` tag.
cask "hypurr" do
  version "0.0.0"
  sha256 "REPLACE_WITH_SHA256"

  url "https://github.com/Ragul84/Hypurr/releases/download/v#{version}/hypurr-macos.dmg"
  name "Hypurr"
  desc "Message your coding agents as bots from your phone, desktop or terminal"
  homepage "https://www.hypurr.dev/"

  auto_updates true
  depends_on macos: :sonoma

  app "Hypurr.app"
  binary "#{appdir}/Hypurr.app/Contents/MacOS/hypurr-host"

  # The app sets up the host service when it opens. Upgrades remove the service too;
  # Homebrew reopens the app afterwards, which sets it up again on the new version.
  uninstall early_script: {
              executable:   "#{appdir}/Hypurr.app/Contents/MacOS/hypurr-host",
              args:         ["uninstall"],
              must_succeed: false,
            },
            quit:         "com.ragul84.Hypurr"

  zap trash: [
    "~/.hypurr",
    "~/Library/Preferences/com.ragul84.Hypurr.plist",
  ]

  caveats "Open Hypurr to start the host, then choose Pair iPhone… in the menu bar."
end
