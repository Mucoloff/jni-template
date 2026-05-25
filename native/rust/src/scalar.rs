use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use crate::engine::Engine;

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Scalar_sum(
    _env: JNIEnv, _class: JClass, a: jint, b: jint,
) -> jint {
    Engine::new().sum(a, b)
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Scalar_subtract(
    _env: JNIEnv, _class: JClass, a: jint, b: jint,
) -> jint {
    Engine::new().subtract(a, b)
}
