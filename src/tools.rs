//! Finds the external programs the app relies on (`yt-dlp` and `ffmpeg`),
//! downloading them from their official GitHub releases when missing.

use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use color_eyre::Result;
use color_eyre::eyre::{bail, eyre};
use ureq::tls::{RootCerts, TlsConfig};

#[cfg(windows)]
const YTDLP_URL: &str = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
#[cfg(target_os = "macos")]
const YTDLP_URL: &str = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos";
#[cfg(all(unix, not(target_os = "macos")))]
const YTDLP_URL: &str = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux";

/// LGPL build from BtbN, the Windows builds linked from ffmpeg.org.
#[cfg(windows)]
const FFMPEG_ZIP_URL: &str = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip";

#[derive(Debug, Clone)]
pub struct Tools {
    pub ytdlp: PathBuf,
    pub ffmpeg: PathBuf,
}

/// Where the app keeps its own copies of the tools.
pub fn tools_dir() -> Result<PathBuf> {
    let base = dirs::data_local_dir().ok_or_else(|| eyre!("no local data folder"))?;
    let dir = base.join("thigh-high").join("bin");
    fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// Makes sure both tools are available, reporting progress through `report`.
pub fn ensure(report: &dyn Fn(String)) -> Result<Tools> {
    let dir = tools_dir()?;

    let ytdlp = match locate("yt-dlp", &dir) {
        Some(path) => {
            // Our own copy goes stale as sites change, so keep it current.
            // A copy on the PATH belongs to the user's package manager.
            if path.starts_with(&dir) {
                report("Updating yt-dlp…".into());
                let _ = quiet(Command::new(&path).arg("-U")).status();
            }
            path
        }
        None => {
            let dest = dir.join(exe("yt-dlp"));
            download(YTDLP_URL, &dest, "yt-dlp", report)?;
            make_executable(&dest)?;
            dest
        }
    };

    let ffmpeg = match locate("ffmpeg", &dir) {
        Some(path) => path,
        None => install_ffmpeg(&dir, report)?,
    };

    Ok(Tools { ytdlp, ffmpeg })
}

/// Looks on the PATH first, then in the app's own folder.
fn locate(name: &str, dir: &Path) -> Option<PathBuf> {
    which::which(name)
        .ok()
        .or_else(|| Some(dir.join(exe(name))).filter(|p| p.is_file()))
}

fn exe(name: &str) -> String {
    format!("{name}{}", std::env::consts::EXE_SUFFIX)
}

#[cfg(windows)]
fn install_ffmpeg(dir: &Path, report: &dyn Fn(String)) -> Result<PathBuf> {
    let archive = dir.join("ffmpeg.zip");
    download(FFMPEG_ZIP_URL, &archive, "ffmpeg", report)?;
    report("Unpacking ffmpeg…".into());

    let mut zip = zip::ZipArchive::new(File::open(&archive)?)?;
    for wanted in ["ffmpeg.exe", "ffprobe.exe"] {
        let index = (0..zip.len())
            .find(|&i| {
                zip.name_for_index(i)
                    .is_some_and(|n| n.ends_with(&format!("/bin/{wanted}")))
            })
            .ok_or_else(|| eyre!("{wanted} not found in the ffmpeg archive"))?;
        let mut entry = zip.by_index(index)?;
        std::io::copy(&mut entry, &mut File::create(dir.join(wanted))?)?;
    }
    drop(zip);
    fs::remove_file(&archive)?;
    Ok(dir.join("ffmpeg.exe"))
}

#[cfg(not(windows))]
fn install_ffmpeg(_dir: &Path, _report: &dyn Fn(String)) -> Result<PathBuf> {
    bail!("ffmpeg not found: install it with your package manager")
}

/// Downloads `url` to `dest`, writing to a temporary file first so an
/// interrupted download never leaves a broken tool behind.
fn download(url: &str, dest: &Path, label: &str, report: &dyn Fn(String)) -> Result<()> {
    report(format!("Downloading {label}…"));
    let agent = ureq::Agent::config_builder()
        .tls_config(
            TlsConfig::builder()
                .root_certs(RootCerts::PlatformVerifier)
                .build(),
        )
        .build()
        .new_agent();
    let response = agent.get(url).call()?;
    let total = response
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());
    let mut reader = response.into_body().into_reader();

    let tmp = dest.with_extension("part");
    let mut file = File::create(&tmp)?;
    let mut buf = vec![0; 64 * 1024];
    let (mut done, mut last_pct) = (0u64, None);
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])?;
        done += n as u64;
        if let Some(total) = total.filter(|&t| t > 0) {
            let pct = done * 100 / total;
            if last_pct != Some(pct) {
                last_pct = Some(pct);
                report(format!("Downloading {label}… {pct}%"));
            }
        }
    }
    file.flush()?;
    drop(file);
    if done == 0 {
        bail!("{label} download was empty");
    }
    fs::rename(&tmp, dest)?;
    Ok(())
}

#[cfg(unix)]
fn make_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))?;
    Ok(())
}

#[cfg(not(unix))]
fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}

/// Detaches a child process from the terminal so its output can't draw over the UI.
pub fn quiet(cmd: &mut Command) -> &mut Command {
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}
