use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jlong};
use crate::engine::Engine;

fn to_engine(h: jlong) -> &'static mut Engine {
    unsafe { &mut *(h as *mut Engine) }
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Engine_create(
    _env: JNIEnv, _class: JClass,
) -> jlong {
    Box::into_raw(Box::new(Engine::new())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Engine_destroy(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if handle != 0 { unsafe { drop(Box::from_raw(handle as *mut Engine)); } }
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Engine_sum(
    _env: JNIEnv, _class: JClass, handle: jlong, a: jint, b: jint,
) -> jint {
    to_engine(handle).sum(a, b)
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Engine_subtract(
    _env: JNIEnv, _class: JClass, handle: jlong, a: jint, b: jint,
) -> jint {
    to_engine(handle).subtract(a, b)
}
