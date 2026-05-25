//! JNI bridge for dev.sweety.jni.RustNatives.
//!
//! Only the native function BODIES (jni_* thunks) live here. The RegisterNatives
//! array and the holder class name are generated from native-api.json by build.rs
//! (into registrations.rs), so they can never drift from the Java declarations.

use jni::objects::{JByteArray, JClass, JLongArray, ReleaseMode};
use jni::sys::{jbyte, jint, jlong, JNI_VERSION_24};
use jni::{JNIEnv, JavaVM, NativeMethod};
use std::ffi::c_void;

use crate::fnv::Fnv;

#[inline]
unsafe fn slice_at<'a>(addr: jlong, len: jlong) -> &'a [u8] {
    if addr == 0 || len <= 0 {
        &[]
    } else {
        std::slice::from_raw_parts(addr as *const u8, len as usize)
    }
}

// --- byte[] paths: copy vs critical ------------------------------------------

unsafe extern "system" fn jni_hash_array(env: JNIEnv, _c: JClass, arr: JByteArray) -> jlong {
    match env.convert_byte_array(&arr) {
        Ok(v) => Fnv::hash(&v) as jlong,
        Err(_) => 0,
    }
}

unsafe extern "system" fn jni_hash_array_crit(
    mut env: JNIEnv,
    _c: JClass,
    arr: JByteArray,
) -> jlong {
    // No copy, but the GC may be paused for the critical region's lifetime.
    match env.get_array_elements_critical(&arr, ReleaseMode::NoCopyBack) {
        Ok(elems) => {
            let s = std::slice::from_raw_parts(elems.as_ptr() as *const u8, elems.len());
            Fnv::hash(s) as jlong
        }
        Err(_) => 0,
    }
}

// --- address paths: zero-copy ------------------------------------------------

unsafe extern "system" fn jni_hash(_e: JNIEnv, _c: JClass, addr: jlong, len: jlong) -> jlong {
    Fnv::hash(slice_at(addr, len)) as jlong
}

unsafe extern "system" fn jni_transform(_e: JNIEnv, _c: JClass, addr: jlong, len: jlong, add: jbyte) {
    if addr == 0 || len <= 0 {
        return;
    }
    let s = std::slice::from_raw_parts_mut(addr as *mut u8, len as usize);
    let a = add as u8;
    for b in s {
        *b = b.wrapping_add(a);
    }
}

unsafe extern "system" fn jni_hash_batch<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    addrs: JLongArray<'l>,
    lens: JLongArray<'l>,
) -> jlong {
    // Returns a jlongArray; declared as jlong so the fn-ptr ABI stays simple —
    // the value is the raw jobject of the array.
    let n = env.get_array_length(&addrs).unwrap_or(0);
    let mut a = vec![0i64; n as usize];
    let mut l = vec![0i64; n as usize];
    let _ = env.get_long_array_region(&addrs, 0, &mut a);
    let _ = env.get_long_array_region(&lens, 0, &mut l);
    let out: Vec<i64> = (0..n as usize)
        .map(|i| Fnv::hash(slice_at(a[i], l[i])) as i64)
        .collect();
    let arr = env.new_long_array(n).unwrap();
    let _ = env.set_long_array_region(&arr, 0, &out);
    arr.into_raw() as jlong
}

// --- streaming handle --------------------------------------------------------

unsafe extern "system" fn jni_create(_e: JNIEnv, _c: JClass) -> jlong {
    Box::into_raw(Box::new(Fnv::new())) as jlong
}
unsafe extern "system" fn jni_free(_e: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        drop(Box::from_raw(h as *mut Fnv));
    }
}
unsafe extern "system" fn jni_update(_e: JNIEnv, _c: JClass, h: jlong, addr: jlong, len: jlong) {
    (*(h as *mut Fnv)).update(slice_at(addr, len));
}
unsafe extern "system" fn jni_digest(_e: JNIEnv, _c: JClass, h: jlong) -> jlong {
    (*(h as *const Fnv)).digest() as jlong
}
unsafe extern "system" fn jni_reset(_e: JNIEnv, _c: JClass, h: jlong) {
    (*(h as *mut Fnv)).reset();
}

// HOLDER_CLASS + registrations(), generated from native-api.json by build.rs.
include!(concat!(env!("OUT_DIR"), "/registrations.rs"));

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let mut env = match vm.get_env() {
        Ok(e) => e,
        Err(_) => return -1,
    };
    let cls = match env.find_class(HOLDER_CLASS) {
        Ok(c) => c,
        Err(_) => return -1,
    };
    if env.register_native_methods(&cls, &registrations()).is_err() {
        return -1;
    }
    JNI_VERSION_24 as jint
}
