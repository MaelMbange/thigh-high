//! JNI functions for the Android app (`io.github.maelmbange.thighhigh.genre.Genre`).
//!
//! The app decodes audio with Android's own codecs and hands the samples
//! here, the same way the terminal app hands over what ffmpeg decoded.

use std::path::Path;
use std::sync::Mutex;
use std::time::Duration;

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jdouble, jint, jlong, jstring};
use thigh_high_core::genre::{self, Classifier};

/// Loading the model takes a moment: done once, on first use.
static CLASSIFIER: Mutex<Option<Classifier>> = Mutex::new(None);

fn throw(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/RuntimeException", message.as_ref());
}

/// Sample rate the excerpt must be decoded at (mono).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_genre_Genre_sampleRate(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    genre::SAMPLE_RATE as jint
}

/// Length of the excerpt the model listens to, in seconds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_genre_Genre_excerptSeconds(
    _env: JNIEnv,
    _class: JClass,
) -> jdouble {
    genre::EXCERPT_SECONDS
}

/// Where the excerpt starts (the middle of the track), in seconds;
/// `duration_ms` ≤ 0 means unknown.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_genre_Genre_excerptStart(
    _env: JNIEnv,
    _class: JClass,
    duration_ms: jlong,
) -> jdouble {
    let duration = (duration_ms > 0).then(|| Duration::from_millis(duration_ms as u64));
    genre::excerpt_start(duration)
}

/// The most likely genre of 16 kHz mono samples, or null if none stands out.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_genre_Genre_detect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    samples: JFloatArray<'local>,
) -> jstring {
    let result = (|| -> Result<Option<String>, String> {
        let len = env.get_array_length(&samples).map_err(|e| e.to_string())?;
        let mut audio = vec![0f32; len as usize];
        env.get_float_array_region(&samples, 0, &mut audio)
            .map_err(|e| e.to_string())?;
        let mut guard = CLASSIFIER.lock().map_err(|e| e.to_string())?;
        if guard.is_none() {
            *guard = Some(Classifier::new().map_err(|e| format!("{e:#}"))?);
        }
        guard
            .as_ref()
            .expect("just loaded")
            .detect(&audio)
            .map_err(|e| format!("{e:#}"))
    })();
    match result {
        Ok(Some(genre)) => env
            .new_string(genre)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Ok(None) => std::ptr::null_mut(),
        Err(message) => {
            throw(&mut env, message);
            std::ptr::null_mut()
        }
    }
}

/// Writes the genre into the file's tags (throws if the file can't be written).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_maelmbange_thighhigh_genre_Genre_saveGenre<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    genre_name: JString<'local>,
) {
    let result = (|| -> Result<(), String> {
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        let name: String = env
            .get_string(&genre_name)
            .map_err(|e| e.to_string())?
            .into();
        genre::save_genre(Path::new(&path), &name).map_err(|e| format!("{e:#}"))
    })();
    if let Err(message) = result {
        throw(&mut env, message);
    }
}
