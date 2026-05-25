//! Direct-buffer bridge: native memory ownership + zero-copy hashing.
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use std::alloc::{alloc, dealloc, Layout};
use std::ptr;

use crate::fnv::Fnv;

fn direct_ptr<'a>(env: &mut JNIEnv<'a>, buf: &JByteBuffer<'a>) -> Option<(*mut u8, usize)> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    Some((ptr, cap))
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_NativeBuffer_hash<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    buffer: JByteBuffer<'a>,
    len: jint,
) -> jlong {
    match direct_ptr(&mut env, &buffer) {
        Some((ptr, _)) => {
            let slice = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
            Fnv::hash(slice) as jlong
        }
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_NativeBuffer_allocate(
    mut env: JNIEnv,
    _class: JClass,
    size: jint,
) -> jni::sys::jobject {
    let n = size as usize;
    let layout = Layout::array::<u8>(n).unwrap();
    let ptr = unsafe { alloc(layout) };
    if ptr.is_null() {
        return ptr::null_mut();
    }
    unsafe { ptr::write_bytes(ptr, 0, n) };
    unsafe { env.new_direct_byte_buffer(ptr, n) }
        .map(|b| b.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_NativeBuffer_free<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    buffer: JByteBuffer<'a>,
) {
    if let Some((ptr, cap)) = direct_ptr(&mut env, &buffer) {
        let layout = Layout::array::<u8>(cap).unwrap();
        unsafe { dealloc(ptr, layout) };
    }
}
