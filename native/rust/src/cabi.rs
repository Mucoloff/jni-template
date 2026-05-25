//! Flat C-ABI surface over the FNV core. No JNI mangling, so these symbols are
//! callable both by the JNI bridge (jni_hashengine.rs) and directly by the JVM's
//! Foreign Function & Memory API (FfmHashEngine). One implementation, two bindings.

use crate::fnv::Fnv;
use std::os::raw::c_void;

#[no_mangle]
pub unsafe extern "C" fn nat_fnv_hash(data: *const u8, len: usize) -> u64 {
    if data.is_null() || len == 0 {
        return Fnv::hash(&[]);
    }
    Fnv::hash(std::slice::from_raw_parts(data, len))
}

#[no_mangle]
pub extern "C" fn nat_fnv_new() -> *mut c_void {
    Box::into_raw(Box::new(Fnv::new())) as *mut c_void
}

#[no_mangle]
pub unsafe extern "C" fn nat_fnv_update(h: *mut c_void, data: *const u8, len: usize) {
    let fnv = &mut *(h as *mut Fnv);
    if !data.is_null() && len != 0 {
        fnv.update(std::slice::from_raw_parts(data, len));
    }
}

#[no_mangle]
pub unsafe extern "C" fn nat_fnv_digest(h: *const c_void) -> u64 {
    (*(h as *const Fnv)).digest()
}

#[no_mangle]
pub unsafe extern "C" fn nat_fnv_reset(h: *mut c_void) {
    (*(h as *mut Fnv)).reset();
}

#[no_mangle]
pub unsafe extern "C" fn nat_fnv_free(h: *mut c_void) {
    if !h.is_null() {
        drop(Box::from_raw(h as *mut Fnv));
    }
}

/// Hash n buffers in a single call — amortizes the JVM<->native crossing cost.
#[no_mangle]
pub unsafe extern "C" fn nat_fnv_hash_batch(
    ptrs: *const *const u8,
    lens: *const usize,
    out: *mut u64,
    n: usize,
) {
    for i in 0..n {
        let p = *ptrs.add(i);
        let l = *lens.add(i);
        let h = if p.is_null() || l == 0 {
            Fnv::hash(&[])
        } else {
            Fnv::hash(std::slice::from_raw_parts(p, l))
        };
        *out.add(i) = h;
    }
}

/// Memory-bound, in-place transform: data[i] += add.
#[no_mangle]
pub unsafe extern "C" fn nat_transform(data: *mut u8, len: usize, add: u8) {
    if data.is_null() {
        return;
    }
    let slice = std::slice::from_raw_parts_mut(data, len);
    for b in slice {
        *b = b.wrapping_add(add);
    }
}
