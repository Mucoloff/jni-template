//! Stateless bridge: hash a Java byte[] in one call.
use jni::objects::{JByteArray, JClass};
use jni::sys::jlong;
use jni::JNIEnv;

use crate::fnv::Fnv;

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Checksum_hash(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jlong {
    // convert_byte_array copies the array out of the JVM heap.
    match env.convert_byte_array(&data) {
        Ok(bytes) => Fnv::hash(&bytes) as jlong,
        Err(_) => 0,
    }
}
