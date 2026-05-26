//! Generates the whole JNI surface from native-api.json (the descriptor emitted by
//! the KSP processor): the flat C-ABI lifecycle bodies, the jni_* thunks (which route
//! through the C-ABI), the RegisterNatives array and JNI_OnLoad. Only the FNV core
//! (fnv.rs) and the loop C-ABI (transform, batch) are hand-written, in cabi.rs.
//! Reads the descriptor path from NATIVE_DESCRIPTOR (set by Gradle).

use std::env;
use std::fs;
use std::path::Path;

fn main() {
    let out_dir = env::var("OUT_DIR").unwrap();
    let dest = Path::new(&out_dir).join("native_generated.rs");
    println!("cargo:rerun-if-env-changed=NATIVE_DESCRIPTOR");

    let descriptor = env::var("NATIVE_DESCRIPTOR").ok();
    let path = match descriptor {
        Some(p) if Path::new(&p).is_file() => p,
        _ => {
            println!("cargo:warning=NATIVE_DESCRIPTOR not set or missing; empty native surface");
            fs::write(&dest, "const HOLDER_CLASS: &str = \"\";\nfn registrations() -> [NativeMethod; 0] { [] }\n").unwrap();
            return;
        }
    };
    println!("cargo:rerun-if-changed={path}");
    let json: serde_json::Value = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
    let core = json["coreType"].as_str().unwrap();

    let mut b = String::new();
    b.push_str("// Generated from native-api.json by build.rs. Do not edit.\n");
    b.push_str("use jni::objects::{JByteArray, JClass, JLongArray, ReleaseMode};\n");
    b.push_str("use jni::sys::{jbyte, jint, jlong, JNI_VERSION_24};\n");
    b.push_str("use jni::{JNIEnv, JavaVM, NativeMethod};\n");
    b.push_str("use std::os::raw::c_void;\n");
    b.push_str(&format!("use crate::fnv::{core};\n"));
    b.push_str("use crate::cabi::{nat_transform, nat_fnv_hash_batch};\n\n");

    // --- C-ABI lifecycle bodies (op != null), uniform *mut c_void pointers -----
    for c in json["cabi"].as_array().unwrap() {
        let op = match c["op"].as_str() {
            Some(o) => o,
            None => continue, // hand-written in cabi.rs (transform, batch)
        };
        let sym = c["symbol"].as_str().unwrap();
        let f = match op {
            "NEW" => format!(
                "#[no_mangle] pub extern \"C\" fn {sym}() -> *mut c_void {{ Box::into_raw(Box::new({core}::new())) as *mut c_void }}\n"
            ),
            "FREE" => format!(
                "#[no_mangle] pub unsafe extern \"C\" fn {sym}(h: *mut c_void) {{ if !h.is_null() {{ drop(Box::from_raw(h as *mut {core})); }} }}\n"
            ),
            "UPDATE" => format!(
                "#[no_mangle] pub unsafe extern \"C\" fn {sym}(h: *mut c_void, data: *mut c_void, len: usize) {{ \
                 let fnv = &mut *(h as *mut {core}); if !data.is_null() && len != 0 {{ fnv.update(std::slice::from_raw_parts(data as *const u8, len)); }} }}\n"
            ),
            "DIGEST" => format!(
                "#[no_mangle] pub unsafe extern \"C\" fn {sym}(h: *mut c_void) -> u64 {{ (*(h as *const {core})).digest() }}\n"
            ),
            "RESET" => format!(
                "#[no_mangle] pub unsafe extern \"C\" fn {sym}(h: *mut c_void) {{ (*(h as *mut {core})).reset(); }}\n"
            ),
            "HASH" => format!(
                "#[no_mangle] pub unsafe extern \"C\" fn {sym}(data: *mut c_void, len: usize) -> u64 {{ \
                 if data.is_null() || len == 0 {{ {core}::hash(&[]) }} else {{ {core}::hash(std::slice::from_raw_parts(data as *const u8, len)) }} }}\n"
            ),
            other => panic!("unknown core op {other}"),
        };
        b.push_str(&f);
    }
    b.push('\n');

    // --- jni_* thunks ----------------------------------------------------------
    for m in json["methods"].as_array().unwrap() {
        let thunk = m["thunk"].as_str().unwrap();
        let target = m["target"].as_str().unwrap();
        let ret = m["ret"].as_str().unwrap();
        let params: Vec<&str> = m["params"].as_array().unwrap().iter().map(|p| p.as_str().unwrap()).collect();
        match m["shape"].as_str().unwrap() {
            "plain" => {
                let sig_params: String = params.iter().enumerate()
                    .map(|(i, k)| format!(", p{i}: {}", if *k == "byte" { "jbyte" } else { "jlong" }))
                    .collect();
                let args: String = params.iter().enumerate().map(|(i, k)| match *k {
                    "ptr" => format!("p{i} as *mut c_void"),
                    "long" => format!("p{i} as usize"),
                    "byte" => format!("p{i} as u8"),
                    _ => panic!("plain arg {k}"),
                }).collect::<Vec<_>>().join(", ");
                let (rty, wrap_l, wrap_r) = match ret {
                    "void" => ("()", "", ""),
                    "ptr" => ("jlong", "", " as jlong"),
                    "long" => ("jlong", "", " as jlong"),
                    _ => panic!("plain ret {ret}"),
                };
                let arrow = if ret == "void" { String::new() } else { format!(" -> {rty}") };
                b.push_str(&format!(
                    "unsafe extern \"system\" fn {thunk}(_e: JNIEnv, _c: JClass{sig_params}){arrow} {{ {wrap_l}{target}({args}){wrap_r} }}\n"
                ));
            }
            "heap" => {
                let crit = m["critical"].as_bool().unwrap_or(false);
                if crit {
                    b.push_str(&format!(
                        "unsafe extern \"system\" fn {thunk}(mut env: JNIEnv, _c: JClass, arr: JByteArray) -> jlong {{\n\
                         \x20   match env.get_array_elements_critical(&arr, ReleaseMode::NoCopyBack) {{\n\
                         \x20       Ok(e) => {target}(e.as_ptr() as *mut c_void, e.len() as usize) as jlong,\n\
                         \x20       Err(_) => 0,\n\
                         \x20   }}\n}}\n"
                    ));
                } else {
                    b.push_str(&format!(
                        "unsafe extern \"system\" fn {thunk}(env: JNIEnv, _c: JClass, arr: JByteArray) -> jlong {{\n\
                         \x20   match env.convert_byte_array(&arr) {{\n\
                         \x20       Ok(v) => {target}(v.as_ptr() as *mut c_void, v.len() as usize) as jlong,\n\
                         \x20       Err(_) => 0,\n\
                         \x20   }}\n}}\n"
                    ));
                }
            }
            "batch" => {
                b.push_str(&format!(
                    "unsafe extern \"system\" fn {thunk}<'l>(env: JNIEnv<'l>, _c: JClass<'l>, addrs: JLongArray<'l>, lens: JLongArray<'l>) -> jlong {{\n\
                     \x20   let n = env.get_array_length(&addrs).unwrap_or(0);\n\
                     \x20   let mut a = vec![0i64; n as usize];\n\
                     \x20   let mut l = vec![0i64; n as usize];\n\
                     \x20   let _ = env.get_long_array_region(&addrs, 0, &mut a);\n\
                     \x20   let _ = env.get_long_array_region(&lens, 0, &mut l);\n\
                     \x20   let mut out = vec![0i64; n as usize];\n\
                     \x20   {target}(a.as_ptr() as *mut c_void, l.as_ptr() as *mut c_void, out.as_mut_ptr() as *mut c_void, n as usize);\n\
                     \x20   let arr = env.new_long_array(n).unwrap();\n\
                     \x20   let _ = env.set_long_array_region(&arr, 0, &out);\n\
                     \x20   arr.into_raw() as jlong\n}}\n"
                ));
            }
            s => panic!("unknown shape {s}"),
        }
    }
    b.push('\n');

    // --- RegisterNatives table + JNI_OnLoad ------------------------------------
    let holder = json["backends"].as_array().unwrap().iter()
        .find(|x| x["name"] == "Rust").unwrap()["holder"].as_str().unwrap();
    let methods = json["methods"].as_array().unwrap();
    b.push_str(&format!("const HOLDER_CLASS: &str = \"{holder}\";\n"));
    b.push_str(&format!("fn registrations() -> [NativeMethod; {}] {{\n    [\n", methods.len()));
    for m in methods {
        let name = m["name"].as_str().unwrap();
        let sig = m["sig"].as_str().unwrap();
        let thunk = m["thunk"].as_str().unwrap();
        b.push_str(&format!(
            "        NativeMethod {{ name: \"{name}\".into(), sig: \"{sig}\".into(), fn_ptr: {thunk} as *mut c_void }},\n"
        ));
    }
    b.push_str("    ]\n}\n\n");
    b.push_str(
        "#[no_mangle]\n\
         pub extern \"system\" fn JNI_OnLoad(vm: JavaVM, _r: *mut c_void) -> jint {\n\
         \x20   let mut env = match vm.get_env() { Ok(e) => e, Err(_) => return -1 };\n\
         \x20   let cls = match env.find_class(HOLDER_CLASS) { Ok(c) => c, Err(_) => return -1 };\n\
         \x20   if env.register_native_methods(&cls, &registrations()).is_err() { return -1; }\n\
         \x20   JNI_VERSION_24 as jint\n}\n"
    );

    fs::write(&dest, b).unwrap();
}
