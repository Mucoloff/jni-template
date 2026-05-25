//! Stateful-handle bridge: a boxed Fnv referenced by an opaque jlong pointer.
use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::fnv::Fnv;

fn as_fnv(h: jlong) -> &'static mut Fnv {
    unsafe { &mut *(h as *mut Fnv) }
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Hasher_create(_env: JNIEnv, _class: JClass) -> jlong {
    Box::into_raw(Box::new(Fnv::new())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Hasher_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut Fnv)) };
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Hasher_update(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    _len: jint,
) {
    if let Ok(bytes) = env.convert_byte_array(&data) {
        as_fnv(handle).update(&bytes);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Hasher_digest(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    as_fnv(handle).digest() as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Hasher_reset(_env: JNIEnv, _class: JClass, handle: jlong) {
    as_fnv(handle).reset();
}
