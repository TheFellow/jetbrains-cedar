// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

use std::str::FromStr;

use cedar_policy::{Policy, PolicySet, Template};
use serde::{Deserialize, Serialize};
#[derive(Debug, Serialize, Deserialize)]
pub struct ExportPolicyResult {
    pub success: bool,
    pub json: Option<String>,
}
pub fn export_policy(input_policy_str: &str) -> ExportPolicyResult {
    let Ok(policy) = Policy::parse(None, input_policy_str) else {
        return ExportPolicyResult {
            success: false,
            json: None,
        };
    };

    let Ok(json) = policy.to_json() else {
        return ExportPolicyResult {
            success: false,
            json: None,
        };
    };

    ExportPolicyResult {
        success: true,
        json: Some(json.to_string()),
    }
}
pub fn export_policies(input_policies_str: &str) -> ExportPolicyResult {
    let Ok(policies) = PolicySet::from_str(input_policies_str) else {
        return ExportPolicyResult {
            success: false,
            json: None,
        };
    };

    let Ok(json) = policies.to_json() else {
        return ExportPolicyResult {
            success: false,
            json: None,
        };
    };

    ExportPolicyResult {
        success: true,
        json: Some(json.to_string()),
    }
}
pub fn export_policy_template(input_policy_template_str: &str) -> ExportPolicyResult {
    let Ok(policy) = Template::parse(None, input_policy_template_str) else {
        return ExportPolicyResult {
            success: false,
            json: None,
        };
    };

    let Ok(json) = policy.to_json() else {
        return ExportPolicyResult {
            success: false,
            json: None,
        };
    };

    ExportPolicyResult {
        success: true,
        json: Some(json.to_string()),
    }
}
