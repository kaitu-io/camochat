//! Internal RNG used by bindings. Always `OsRng` — no caller-supplied RNG.
//!
//! On iOS this routes through `SecRandomCopyBytes`; on Android through
//! `java.security.SecureRandom`. On Linux/macOS dev, `/dev/urandom`.

use rand::rngs::OsRng;
use rand_core::{CryptoRng, RngCore};

/// Returns the platform CSPRNG.
#[must_use]
pub fn os_rng() -> impl CryptoRng + RngCore {
    OsRng
}

#[cfg(test)]
mod tests {
    use super::os_rng;
    use rand_core::RngCore;

    #[test]
    fn os_rng_produces_distinct_bytes() {
        let mut rng = os_rng();
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        rng.fill_bytes(&mut a);
        rng.fill_bytes(&mut b);
        assert_ne!(a, b);
    }
}
