// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

//! JVM bridge for the Cedar SDK.
//!
//! Upstream (vscode-cedar-wasm) exposes each function through wasm-bindgen.
//! The JetBrains plugin runs this module in the JVM (Chicory), so instead a
//! single `call` export takes a JSON request `{"fn": "...", "args": [...]}`
//! and returns the JSON-serialized upstream result struct. The per-module
//! logic is kept byte-for-byte close to upstream to keep semports mechanical.

mod entities_validator;
mod format;
mod policy;
mod policy_validator;
mod schema_translate;
mod schema_validator;
mod syntax_validator;
mod utils;
mod validate_message;

use serde::Deserialize;
use serde_json::Value;

#[derive(Deserialize)]
struct Request {
    #[serde(rename = "fn")]
    function: String,
    #[serde(default)]
    args: Vec<Value>,
}

fn arg_str(args: &[Value], i: usize) -> Result<&str, String> {
    args.get(i)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("argument {i} must be a string"))
}

fn arg_i64(args: &[Value], i: usize) -> Result<i64, String> {
    args.get(i)
        .and_then(Value::as_i64)
        .ok_or_else(|| format!("argument {i} must be an integer"))
}

fn to_json<T: serde::Serialize>(value: T) -> Result<Value, String> {
    serde_json::to_value(value).map_err(|e| e.to_string())
}

pub fn dispatch(request: &str) -> Result<Value, String> {
    let req: Request = serde_json::from_str(request).map_err(|e| e.to_string())?;
    let a = &req.args;
    match req.function.as_str() {
        "getCedarSDKVersion" => to_json(utils::get_cedar_sdk_version()),
        "validateSyntax" => to_json(syntax_validator::validate_syntax(arg_str(a, 0)?)),
        "validatePolicySchemaJSON" => to_json(policy_validator::validate_policy_schema_json(
            arg_str(a, 0)?,
            arg_str(a, 1)?,
        )),
        "validatePolicySchemaCedar" => to_json(policy_validator::validate_policy_schema_cedar(
            arg_str(a, 0)?,
            arg_str(a, 1)?,
        )),
        "validateSchemaJSON" => to_json(schema_validator::validate_schema_json(arg_str(a, 0)?)),
        "validateSchemaCedar" => to_json(schema_validator::validate_schema_cedar(arg_str(a, 0)?)),
        "validateEntitiesSchemaJSON" => to_json(entities_validator::validate_entities_schema_json(
            arg_str(a, 0)?,
            arg_str(a, 1)?,
        )),
        "validateEntitiesSchemaCedar" => to_json(
            entities_validator::validate_entities_schema_cedar(arg_str(a, 0)?, arg_str(a, 1)?),
        ),
        "formatPolicies" => to_json(format::format_policies(
            arg_str(a, 0)?,
            arg_i64(a, 1)? as usize,
            arg_i64(a, 2)? as isize,
        )),
        "exportPolicy" => to_json(policy::export_policy(arg_str(a, 0)?)),
        "exportPolicies" => to_json(policy::export_policies(arg_str(a, 0)?)),
        "exportPolicyTemplate" => to_json(policy::export_policy_template(arg_str(a, 0)?)),
        "translateSchemaFromJSON" => {
            to_json(schema_translate::translate_schema_from_json(arg_str(a, 0)?))
        }
        "translateSchemaToJSON" => to_json(schema_translate::translate_schema_to_json(arg_str(a, 0)?)),
        other => Err(format!("unknown function: {other}")),
    }
}

/// Allocates `len` bytes in linear memory for the host to write a request into.
#[no_mangle]
pub extern "C" fn cedar_alloc(len: usize) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(len);
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr
}

/// Frees a buffer previously returned by `cedar_alloc` or `cedar_call`.
#[no_mangle]
pub unsafe extern "C" fn cedar_free(ptr: *mut u8, len: usize) {
    drop(Vec::from_raw_parts(ptr, 0, len));
}

/// Runs a JSON request and returns `(ptr << 32) | len` of a JSON response
/// `{"ok": <result>}` or `{"err": "<message>"}`. Panics are reported as errors.
#[no_mangle]
pub unsafe extern "C" fn cedar_call(ptr: *const u8, len: usize) -> u64 {
    let bytes = std::slice::from_raw_parts(ptr, len);
    let response = match std::str::from_utf8(bytes) {
        Err(e) => serde_json::json!({ "err": e.to_string() }),
        Ok(request) => match std::panic::catch_unwind(|| dispatch(request)) {
            Ok(Ok(value)) => serde_json::json!({ "ok": value }),
            Ok(Err(e)) => serde_json::json!({ "err": e }),
            Err(_) => serde_json::json!({ "err": "cedar panicked" }),
        },
    };
    let mut out = serde_json::to_vec(&response).unwrap_or_default().into_boxed_slice();
    let out_len = out.len();
    let out_ptr = out.as_mut_ptr();
    std::mem::forget(out);
    ((out_ptr as u64) << 32) | out_len as u64
}
