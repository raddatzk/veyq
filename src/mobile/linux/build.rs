//! Reads what the app is built from but does not keep (ADR 0123's Consequences, the bridge and the Linux app): the bridge's Rust
//! types and the words' Rust and gettext catalogs and the theme, all generated under `$VEYQ_GENERATED` (run.py mounts
//! `src/mobile/build/generated` there), and links `libveyq.so` from `$VEYQ_LIB_DIR`. Into `OUT_DIR` it writes:
//! `bridge.rs` and `words.rs` as they are, `locale/<lang>/LC_MESSAGES/veyq.mo` (msgfmt), and `veyq.gresource`
//! (glib-compile-resources over the theme, under the app's resource base path, where libadwaita finds `style.css`).
//! The app's id comes from `$VEYQ_APP_ID` (run.py reads it from `src/mobile/desktop-identity.json`, ADR 0133 decision 12);
//! where the catalogs are read from is `$VEYQ_LOCALE_DIR` when the build sets it (the Flatpak's `/app/share/locale`),
//! else the build's own `OUT_DIR/locale`.
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    let generated = PathBuf::from(need("VEYQ_GENERATED"));
    let lib_dir = need("VEYQ_LIB_DIR");
    let app_id = need("VEYQ_APP_ID");
    if app_id.is_empty() || !app_id.split('.').all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')) {
        panic!("VEYQ_APP_ID {app_id:?} is no application id (dot-separated, letters, digits and _)");
    }
    let resource_base = format!("/{}", app_id.replace('.', "/"));
    println!("cargo:rerun-if-env-changed=VEYQ_APP_ID");
    println!("cargo:rerun-if-env-changed=VEYQ_LOCALE_DIR");
    println!("cargo:rustc-env=VEYQ_APP_ID={app_id}");
    println!("cargo:rustc-env=VEYQ_RESOURCE_BASE={resource_base}");
    let out = PathBuf::from(env::var("OUT_DIR").expect("cargo sets OUT_DIR"));
    println!("cargo:rerun-if-env-changed=VEYQ_GENERATED");
    println!("cargo:rerun-if-env-changed=VEYQ_LIB_DIR");

    copy(&generated.join("bridge/bridge.rs"), &out.join("bridge.rs"));
    let words = generated.join("linux/words");
    copy(&words.join("words.rs"), &out.join("words.rs"));

    let locale = out.join("locale");
    for po in entries(&words).into_iter().filter(|p| p.extension().is_some_and(|e| e == "po")) {
        let lang = po.file_stem().and_then(|s| s.to_str()).expect("a <lang>.po");
        let dir = locale.join(lang).join("LC_MESSAGES");
        fs::create_dir_all(&dir).expect("the locale tree");
        run(Command::new("msgfmt").arg("--check").arg("-o").arg(dir.join("veyq.mo")).arg(&po));
        println!("cargo:rerun-if-changed={}", po.display());
    }
    let bound = env::var("VEYQ_LOCALE_DIR").ok().filter(|d| !d.is_empty()).unwrap_or_else(|| locale.display().to_string());
    println!("cargo:rustc-env=VEYQ_LOCALE_DIR={bound}");

    let theme = generated.join("linux/theme");
    let files = files_under(&theme);
    if !files.iter().any(|f| f == Path::new("style.css")) {
        panic!("{}: no style.css — run.py writes it with generate_theme.py --gtk", theme.display());
    }
    let mut xml = format!("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gresources>\n  <gresource prefix=\"{resource_base}\">\n");
    for f in &files {
        xml += &format!("    <file>{}</file>\n", f.display());
        println!("cargo:rerun-if-changed={}", theme.join(f).display());
    }
    xml += "  </gresource>\n</gresources>\n";
    let manifest = out.join("veyq.gresource.xml");
    fs::write(&manifest, xml).expect("the resource manifest");
    run(Command::new("glib-compile-resources")
        .arg("--sourcedir")
        .arg(&theme)
        .arg("--target")
        .arg(out.join("veyq.gresource"))
        .arg(&manifest));

    println!("cargo:rustc-link-search=native={lib_dir}");
    println!("cargo:rustc-link-lib=dylib=veyq");
}

fn need(name: &str) -> String {
    env::var(name).unwrap_or_else(|_| panic!("{name} is not set: build through src/mobile/linux/run.py"))
}

fn copy(from: &Path, to: &Path) {
    fs::copy(from, to).unwrap_or_else(|e| panic!("{}: {e} — run.py generates it first", from.display()));
    println!("cargo:rerun-if-changed={}", from.display());
}

fn entries(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(dir)
        .unwrap_or_else(|e| panic!("{}: {e}", dir.display()))
        .map(|e| e.expect("a directory entry").path())
        .collect();
    v.sort();
    v
}

/// Every file under [root], relative to it, sorted.
fn files_under(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for p in entries(&dir) {
            if p.is_dir() {
                stack.push(p);
            } else {
                out.push(p.strip_prefix(root).expect("under the root").to_path_buf());
            }
        }
    }
    out.sort();
    out
}

fn run(cmd: &mut Command) {
    let status = cmd.status().unwrap_or_else(|e| panic!("{cmd:?}: {e}"));
    assert!(status.success(), "{cmd:?} failed: {status}");
}
