// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

pub fn get_cedar_sdk_version() -> String {
    std::env!("CEDAR_VERSION").to_string()
}
