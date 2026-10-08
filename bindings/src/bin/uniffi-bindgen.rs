//! Local uniffi-bindgen binary so `cargo run --bin uniffi-bindgen --` works
//! inside the workspace without depending on a globally-installed copy.

fn main() {
    uniffi::uniffi_bindgen_main();
}
