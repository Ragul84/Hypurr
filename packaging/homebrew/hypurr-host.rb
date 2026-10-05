# Homebrew formula template for the Ragul84/homebrew-hypurr tap.
# The `homebrew` job in .github/workflows/host.yml fills in the version and the four
# sha256 values (in this order: mac arm, mac intel, linux arm, linux intel) on every `v*` tag.
class HypurrHost < Formula
  desc "Host that runs coding agents (Claude Code, Codex, Cursor…) as Hypurr bots"
  homepage "https://www.hypurr.dev"
  version "0.0.0"
  license "MIT"

  base = "https://github.com/Ragul84/Hypurr/releases/download/v#{version}/hypurr-host"

  on_macos do
    on_arm do
      url "#{base}-macos-arm64.tar.gz"
      sha256 "REPLACE_WITH_SHA256"
    end
    on_intel do
      url "#{base}-macos-x86_64.tar.gz"
      sha256 "REPLACE_WITH_SHA256"
    end
  end

  on_linux do
    on_arm do
      url "#{base}-linux-arm64.tar.gz"
      sha256 "REPLACE_WITH_SHA256"
    end
    on_intel do
      url "#{base}-linux-x86_64.tar.gz"
      sha256 "REPLACE_WITH_SHA256"
    end
  end

  def install
    bin.install "hypurr-host"
  end

  def caveats
    <<~EOS
      Start the host in the background and pair your iPhone:
        hypurr-host install
        hypurr-host pair
    EOS
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/hypurr-host --version")
  end
end
