// ChencangShared — umbrella module re-export.
//
// Re-exports the uniffi-generated Chencang bindings so app/extension targets
// only need to depend on ChencangShared.
@_exported import Chencang

import Foundation

/// Compile-time version stamp; bumped per binding upgrade.
public enum ChencangSharedInfo {
    public static let bindingsTag = "bindings-v0.1.0-rc1"
}
