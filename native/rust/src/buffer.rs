use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::jint;
use std::alloc::{alloc, dealloc, Layout};
use std::ptr;

fn get_direct_ptr<'a>(env: &mut JNIEnv<'a>, buf: &JByteBuffer<'a>) -> Option<(*mut u8, usize)> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()? as usize;
    Some((ptr.as_ptr(), cap))
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Buffer_process(
    mut env: JNIEnv, _class: JClass, buffer: JByteBuffer, len: jint,
) {
    if let Some((ptr, _)) = get_direct_ptr(&mut env, &buffer) {
        let n = len as usize;
        unsafe {
            for i in 0..n { *ptr.add(i) ^= 0xFF; }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Buffer_allocate(
    mut env: JNIEnv, _class: JClass, size: jint,
) -> jni::sys::jobject {
    let n = size as usize;
    let layout = Layout::array::<u8>(n).unwrap();
    let ptr = unsafe { alloc(layout) };
    if ptr.is_null() { return ptr::null_mut(); }
    unsafe { ptr::write_bytes(ptr, 0, n); }
    env.new_direct_byte_buffer(ptr, n)
        .map(|b| b.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_sweety_Buffer_free(
    mut env: JNIEnv, _class: JClass, buffer: JByteBuffer,
) {
    if let Some((ptr, cap)) = get_direct_ptr(&mut env, &buffer) {
        let layout = Layout::array::<u8>(cap).unwrap();
        unsafe { dealloc(ptr, layout); }
    }
}
