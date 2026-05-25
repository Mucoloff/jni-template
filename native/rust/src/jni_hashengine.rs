//! JNI bridge for dev.sweety.jni.JniHashEngine, registered explicitly via
//! RegisterNatives in JNI_OnLoad (no name-based resolution). Zero-copy paths take
//! a raw address as jlong; the byte[] paths (copy vs critical) contrast against it.

use jni::objects::{JByteArray, JClass, JLongArray, ReleaseMode};
use jni::sys::{jbyte, jint, jlong, JNI_VERSION_1_8};
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

unsafe extern "system" fn hash_array(env: JNIEnv, _c: JClass, arr: JByteArray) -> jlong {
    match env.convert_byte_array(&arr) {
        Ok(v) => Fnv::hash(&v) as jlong,
        Err(_) => 0,
    }
}

unsafe extern "system" fn hash_array_critical(
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

unsafe extern "system" fn hash_addr(_e: JNIEnv, _c: JClass, addr: jlong, len: jlong) -> jlong {
    Fnv::hash(slice_at(addr, len)) as jlong
}

unsafe extern "system" fn transform_addr(_e: JNIEnv, _c: JClass, addr: jlong, len: jlong, add: jbyte) {
    if addr == 0 || len <= 0 {
        return;
    }
    let s = std::slice::from_raw_parts_mut(addr as *mut u8, len as usize);
    let a = add as u8;
    for b in s {
        *b = b.wrapping_add(a);
    }
}

unsafe extern "system" fn hash_batch<'l>(
    env: JNIEnv<'l>,
    _c: JClass<'l>,
    addrs: JLongArray<'l>,
    lens: JLongArray<'l>,
) -> jlong {
    // Returns a jlongArray; declared in Rust as jlong to keep the fn pointer
    // ABI simple — the actual returned value is the raw jobject of the array.
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

unsafe extern "system" fn s_create(_e: JNIEnv, _c: JClass) -> jlong {
    Box::into_raw(Box::new(Fnv::new())) as jlong
}
unsafe extern "system" fn s_free(_e: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 {
        drop(Box::from_raw(h as *mut Fnv));
    }
}
unsafe extern "system" fn s_update(_e: JNIEnv, _c: JClass, h: jlong, addr: jlong, len: jlong) {
    (*(h as *mut Fnv)).update(slice_at(addr, len));
}
unsafe extern "system" fn s_digest(_e: JNIEnv, _c: JClass, h: jlong) -> jlong {
    (*(h as *const Fnv)).digest() as jlong
}
unsafe extern "system" fn s_reset(_e: JNIEnv, _c: JClass, h: jlong) {
    (*(h as *mut Fnv)).reset();
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let mut env = match vm.get_env() {
        Ok(e) => e,
        Err(_) => return -1,
    };
    let cls = match env.find_class("dev/sweety/jni/RustNatives") {
        Ok(c) => c,
        Err(_) => return -1,
    };
    let methods = [
        NativeMethod { name: "nHashArray".into(),         sig: "([B)J".into(),    fn_ptr: hash_array as *mut c_void },
        NativeMethod { name: "nHashArrayCritical".into(), sig: "([B)J".into(),    fn_ptr: hash_array_critical as *mut c_void },
        NativeMethod { name: "nHashAddr".into(),          sig: "(JJ)J".into(),    fn_ptr: hash_addr as *mut c_void },
        NativeMethod { name: "nTransform".into(),         sig: "(JJB)V".into(),   fn_ptr: transform_addr as *mut c_void },
        NativeMethod { name: "nHashBatch".into(),         sig: "([J[J)[J".into(), fn_ptr: hash_batch as *mut c_void },
        NativeMethod { name: "nsCreate".into(),           sig: "()J".into(),      fn_ptr: s_create as *mut c_void },
        NativeMethod { name: "nsFree".into(),             sig: "(J)V".into(),     fn_ptr: s_free as *mut c_void },
        NativeMethod { name: "nsUpdate".into(),           sig: "(JJJ)V".into(),   fn_ptr: s_update as *mut c_void },
        NativeMethod { name: "nsDigest".into(),           sig: "(J)J".into(),     fn_ptr: s_digest as *mut c_void },
        NativeMethod { name: "nsReset".into(),            sig: "(J)V".into(),     fn_ptr: s_reset as *mut c_void },
    ];
    if env.register_native_methods(&cls, &methods).is_err() {
        return -1;
    }
    JNI_VERSION_1_8 as jint
}
