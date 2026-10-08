//! Helper for `infra/media/smoke.sh`. Ignored by default.
//!   SMOKE_DIR=/tmp/x cargo test -p chencang-core --test media_smoke_helper -- --ignored make
//!   SMOKE_DIR=/tmp/x cargo test -p chencang-core --test media_smoke_helper -- --ignored check

use chencang_core::blob::{decrypt_media_blob, encrypt_media_blob};
use chencang_core::payload::MEDIA_KIND_IMAGE;
use rand::rngs::OsRng;
use std::path::PathBuf;

fn dir() -> PathBuf {
    PathBuf::from(std::env::var("SMOKE_DIR").expect("SMOKE_DIR"))
}

#[test]
#[ignore = "smoke helper"]
fn make() {
    let plain = b"chencang smoke".repeat(100);
    let sealed = encrypt_media_blob(&mut OsRng, &plain, MEDIA_KIND_IMAGE).unwrap();
    std::fs::write(dir().join("secret"), sealed.blob_secret).unwrap();
    std::fs::write(dir().join("plain"), &plain).unwrap();
    std::fs::write(dir().join("blob"), &sealed.blob).unwrap();
    std::fs::write(dir().join("id"), sealed.blob_id).unwrap();
}

#[test]
#[ignore = "smoke helper"]
fn check() {
    let secret: [u8; 32] = std::fs::read(dir().join("secret"))
        .unwrap()
        .try_into()
        .unwrap();
    let got = std::fs::read(dir().join("downloaded")).unwrap();
    let plain = decrypt_media_blob(&got, &secret, MEDIA_KIND_IMAGE).unwrap();
    assert_eq!(plain, std::fs::read(dir().join("plain")).unwrap());
}
