# 出厂配置单

`payload.json` 是配置单的唯一权威源（来源列表、中转站、分享站、Android 最低/最新版本）。改动后需递增 `seq`，用 `cargo run -p xtask -- sign-config` 重新签名生成 `chencang-config.json`，再由发布脚本传到各来源的 `/c/` 路径；出厂内置的版本随 App 打包。
