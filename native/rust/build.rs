//! Generates the JNI RegisterNatives array from native-api.json (the descriptor
//! emitted by the annotation processor), so the Rust table never drifts from the
//! Java declarations. Reads the path from the NATIVE_DESCRIPTOR env var (set by
//! Gradle). If unset, emits an empty table and a warning.

use std::env;
use std::fs;
use std::path::Path;

fn main() {
    let out_dir = env::var("OUT_DIR").unwrap();
    let dest = Path::new(&out_dir).join("registrations.rs");

    let descriptor = env::var("NATIVE_DESCRIPTOR").ok();
    let mut body = String::new();

    match descriptor {
        Some(path) if Path::new(&path).is_file() => {
            println!("cargo:rerun-if-changed={path}");
            let text = fs::read_to_string(&path).expect("read descriptor");
            let json: serde_json::Value = serde_json::from_str(&text).expect("parse descriptor");

            let holder = json["backends"]
                .as_array()
                .and_then(|bs| bs.iter().find(|b| b["name"] == "Rust"))
                .map(|b| b["holder"].as_str().unwrap().to_string())
                .expect("Rust backend in descriptor");

            let methods = json["methods"].as_array().expect("methods array");
            body.push_str(&format!("const HOLDER_CLASS: &str = \"{holder}\";\n"));
            body.push_str(&format!(
                "fn registrations() -> [NativeMethod; {}] {{\n    [\n",
                methods.len()
            ));
            for m in methods {
                let name = m["name"].as_str().unwrap();
                let sig = m["sig"].as_str().unwrap();
                let thunk = m["thunk"].as_str().unwrap();
                body.push_str(&format!(
                    "        NativeMethod {{ name: \"{name}\".into(), sig: \"{sig}\".into(), fn_ptr: {thunk} as *mut c_void }},\n"
                ));
            }
            body.push_str("    ]\n}\n");
        }
        _ => {
            println!("cargo:warning=NATIVE_DESCRIPTOR not set or missing; empty registration table");
            body.push_str("const HOLDER_CLASS: &str = \"\";\n");
            body.push_str("fn registrations() -> [NativeMethod; 0] { [] }\n");
        }
    }

    println!("cargo:rerun-if-env-changed=NATIVE_DESCRIPTOR");
    fs::write(&dest, body).expect("write registrations.rs");
}
