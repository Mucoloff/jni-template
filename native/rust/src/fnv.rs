//! FNV-1a 64-bit hash. Tiny, dependency-free, CPU-bound — a realistic reason to
//! drop into native code. Shared by all three JNI bridges (checksum, buffer,
//! hasher) so the Rust backend matches the C++ one bit-for-bit.

pub const OFFSET_BASIS: u64 = 0xcbf2_9ce4_8422_2325;
pub const PRIME: u64 = 0x0000_0100_0000_01b3;

pub struct Fnv {
    state: u64,
}

impl Fnv {
    pub fn new() -> Self {
        Fnv { state: OFFSET_BASIS }
    }

    /// Incremental update — feed a chunk of bytes into the running hash.
    pub fn update(&mut self, data: &[u8]) {
        let mut h = self.state;
        for &b in data {
            h ^= b as u64;
            h = h.wrapping_mul(PRIME);
        }
        self.state = h;
    }

    pub fn digest(&self) -> u64 {
        self.state
    }

    pub fn reset(&mut self) {
        self.state = OFFSET_BASIS;
    }

    /// One-shot convenience: hash a buffer in a single call.
    pub fn hash(data: &[u8]) -> u64 {
        let mut f = Fnv::new();
        f.update(data);
        f.digest()
    }
}
