// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

//! DIVERGENCE (semport/DIVERGENCES.md#multiple-schema-files): not in upstream vscode-cedar-wasm.
//!
//! When several schema files are configured they are fragments of one schema: a file may use types declared
//! in another, so it can't be validated or translated on its own (both resolve every type). These parse a
//! single fragment without resolving types: syntax errors for that file, and its JSON form for combining.

use cedar_policy::SchemaFragment;
use miette::Diagnostic;
use serde::{Deserialize, Serialize};

use crate::schema_translate::TranslateSchemaResult;
use crate::validate_message::ValidateMessage;

#[derive(Debug, Serialize, Deserialize)]
pub struct ValidateSchemaFragmentResult {
    pub success: bool,
    errors: Option<Vec<ValidateMessage>>,
}

fn message<T: Diagnostic>(diagnostic: &T) -> ValidateMessage {
    let label = diagnostic.labels().and_then(|mut labels| labels.next());
    ValidateMessage {
        message: diagnostic.help().map_or_else(
            || format!("{diagnostic}"),
            |help| format!("{diagnostic}\n{help}"),
        ),
        offset: label.as_ref().map_or(0, |l| l.offset()),
        length: label.as_ref().map_or(0, |l| l.len()),
    }
}

fn result(errors: Vec<ValidateMessage>) -> ValidateSchemaFragmentResult {
    ValidateSchemaFragmentResult {
        success: errors.is_empty(),
        errors: if errors.is_empty() { None } else { Some(errors) },
    }
}

pub fn validate_schema_fragment_cedar(src: &str) -> ValidateSchemaFragmentResult {
    match SchemaFragment::from_cedarschema_str(src) {
        Ok(_) => result(Vec::new()),
        Err(e) => result(vec![message(&e)]),
    }
}

pub fn validate_schema_fragment_json(src: &str) -> ValidateSchemaFragmentResult {
    match SchemaFragment::from_json_str(src) {
        Ok(_) => result(Vec::new()),
        Err(e) => result(vec![message(&e)]),
    }
}

pub fn translate_schema_fragment_to_json(src: &str) -> TranslateSchemaResult {
    match SchemaFragment::from_cedarschema_str(src).map(|(fragment, _)| fragment.to_json_string()) {
        Ok(Ok(json)) => TranslateSchemaResult { success: true, schema: Some(json), error: None },
        Ok(Err(err)) => TranslateSchemaResult { success: false, schema: None, error: Some(format!("Translate error: {err}")) },
        Err(err) => TranslateSchemaResult { success: false, schema: None, error: Some(format!("Translate error: {err}")) },
    }
}
