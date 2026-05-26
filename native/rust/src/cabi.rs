//! Hand-written flat C-ABI over the FNV core: only the loop parts (transform, batch).
//! The lifecycle/hash symbols (nat_fnv_new/free/update/digest/reset/hash) are generated
//! into native_generated.rs from native-api.json. Pointers are *mut c_void so the
//! generated thunks pass addresses uniformly; the FFM downcalls (ADDRESS) are unaffected.

use crate::fnv::Fnv;
use std::os::raw::c_void;

/// Hash n buffers in a single call — amortizes the JVM<->native crossing cost.
#[no_mangle]
pub unsafe extern "C" fn nat_fnv_hash_batch(ptrs: *mut c_void, lens: *mut c_void, out: *mut c_void, n: usize) {
    let ptrs = ptrs as *const *const u8;
    let lens = lens as *const usize;
    let out = out as *mut u64;
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
pub unsafe extern "C" fn nat_transform(data: *mut c_void, len: usize, add: u8) {
    if data.is_null() {
        return;
    }
    let slice = std::slice::from_raw_parts_mut(data as *mut u8, len);
    for b in slice {
        *b = b.wrapping_add(add);
    }
}
