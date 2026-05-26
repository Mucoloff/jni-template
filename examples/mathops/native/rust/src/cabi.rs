//! Hand-written native core for mathops (Rust): the flat C-ABI the generated thunks
//! and the FFM downcalls route through. Uniform integer widths matching the framework
//! lowering (long -> usize/u64, int -> i32, byte -> u8).

#[no_mangle]
pub extern "C" fn nat_add(a: usize, b: usize) -> u64 {
    (a as u64).wrapping_add(b as u64)
}

#[no_mangle]
pub extern "C" fn nat_imul(a: i32, b: i32) -> i32 {
    a.wrapping_mul(b)
}

#[no_mangle]
pub extern "C" fn nat_neg(x: u8) -> u8 {
    (-(x as i32)) as u8
}
