// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ValidateMessage {
    pub message: String,
    pub offset: usize,
    pub length: usize,
}
