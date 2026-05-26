//! Hand-written native core for buffer (Rust): off-heap buffer ops over *mut c_void
//! (the generated thunks pass MemorySegment addresses as *mut c_void).

use std::os::raw::c_void;

#[no_mangle]
pub unsafe extern "C" fn nat_fill(buf: *mut c_void, len: usize, v: u8) {
    let s = std::slice::from_raw_parts_mut(buf as *mut u8, len);
    for b in s {
        *b = v;
    }
}

#[no_mangle]
pub unsafe extern "C" fn nat_sum(buf: *mut c_void, len: usize) -> u64 {
    std::slice::from_raw_parts(buf as *const u8, len).iter().map(|&b| b as u64).sum()
}

#[no_mangle]
pub unsafe extern "C" fn nat_copy(dst: *mut c_void, src: *mut c_void, len: usize) {
    std::ptr::copy_nonoverlapping(src as *const u8, dst as *mut u8, len);
}
