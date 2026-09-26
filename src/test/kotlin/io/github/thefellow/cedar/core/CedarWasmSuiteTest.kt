// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.wasm.Cedar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Port of upstream src/test/suite/cedar-wasm.test.ts. */

@Suppress("ClassName", "UNNECESSARY_SAFE_CALL", "CanBeVal")
class CedarWasmSuiteTest {
  // suite: Cedar WASM format Suite
  @Test
  fun `formatPolicies`() {
    val rawPolicy = "permit (\n  principal,\n    action,\n       resource\n);"
    val result = Cedar.formatPolicies(
      rawPolicy,
      80,
      4
    )
    assertEquals(true, result.success)
    assertEquals("permit (principal, action, resource);\n", result.policy)
  }

  @Test
  fun `formatPolicies 2`() {
    val rawPolicy = "forbid (principal, action, resource) \n    when { principal has tenant && resource has tenant && principal.tenant != resource.tenant};"
    val formattedPolicy = "forbid (principal, action, resource)\nwhen\n{\n    principal has tenant &&\n    resource has tenant &&\n    principal.tenant != resource.tenant\n};\n"
    val result = Cedar.formatPolicies(
      rawPolicy,
      80,
      4
    )
    assertEquals(true, result.success)

    assertEquals(formattedPolicy, result.policy)
  }

  @Test
  fun `formatPolicies single line with trailing comment`() {
    val formattedPolicy = "forbid (principal, action, resource); // trailing comment\n"
    val result = Cedar.formatPolicies(
      formattedPolicy,
      80,
      4
    )
    assertEquals(true, result.success)
    assertEquals(formattedPolicy, result.policy)
  }

  @Test
  fun `formatPolicies single line with trailing comment line`() {
    val formattedPolicy = "forbid (principal, action, resource);\n// trailing comment line\n"
    val result = Cedar.formatPolicies(
      formattedPolicy,
      80,
      4
    )
    assertEquals(true, result.success)
    assertEquals(formattedPolicy, result.policy)
  }

  @Test
  fun `formatPolicies single line with clause trailing comment`() {
    val formattedPolicy = "forbid (principal, action, resource)\nwhen\n{\n    principal == resource && // keeps comment\n    principal.x == resource.x // loses comment\n};\n"
    val result = Cedar.formatPolicies(
      formattedPolicy,
      80,
      4
    )
    assertEquals(true, result.success)
    assertEquals(formattedPolicy, result.policy)
  }

  @Test
  fun `formatPolicies with comments`() {
    val formattedPolicy = "forbid (principal, action, resource) // effect comment\nwhen\n{\n    principal has tenant && // line 1 comment\n    resource has tenant && // line 2 comment\n    principal.tenant != resource.tenant // line 3 comment\n};\n"
    val result = Cedar.formatPolicies(
      formattedPolicy,
      80,
      4
    )
    assertEquals(true, result.success)
    assertEquals(formattedPolicy, result.policy)
  }

  // suite: Cedar WASM export Suite
  @Test
  fun `exportPolicies`() {
    val rawPolicy = "permit (\n  principal == User::\"bob\",\n  action == Action::\"view\",\n  resource == Album::\"trip\"\n)\nwhen { principal.age > 18 };"
    val result = Cedar.exportPolicy(rawPolicy)
    assertEquals(true, result.success)
  }

  // suite: Cedar WASM validate Suite
  @Test
  fun `validate_syntax_passes_1_policy`() {
    val policy = "permit(principal, action, resource);"
    val result = Cedar.validateSyntax(policy)
    assertEquals(true, result.success)
    assertEquals(1, result.policies)
  }

  @Test
  fun `validate_syntax_passes_multi_policy`() {
    val policy =
      "forbid(principal, action, resource); permit(principal == User::\"alice\", action == Action::\"view\", resource in Albums::\"alice_albums\");"
    val result = Cedar.validateSyntax(policy)
    assertEquals(true, result.success)
    assertEquals(2, result.policies)
  }

  @Test
  fun `validate_syntax_returns_validation_errors_when_expected_1_policy`() {
    val policy = "permit(2pac, action, resource)"
    val result = Cedar.validateSyntax(policy)
    assertEquals(false, result.success)
  }

  @Test
  fun `validate_syntax_returns_validation_errors_when_expected_multi_policy`() {
    val policy =
      "forbid(principal, action, resource);permit(2pac, action, resource)"
    val result = Cedar.validateSyntax(policy)
    assertEquals(false, result.success)
  }

  @Test
  fun `validate_syntax_returns_validation_errors_when_poorly_formed`() {
    val policy = "permit (principal, action, resource) whenless {};"
    val result = Cedar.validateSyntax(policy)
    assertEquals(false, result.success)
    assertNotNull(result.errors)
    assertEquals(1, result.errors?.size)
    assertEquals("invalid policy condition: whenless\ncondition must be either `when` or `unless`", result.errors?.get(0)?.message)
    assertEquals(37, result.errors?.get(0)?.offset)
    assertEquals(8, result.errors?.get(0)?.length)
  }

  @Test
  fun `validate_syntax_returns_validation_errors_when_expected_commas`() {
    val policy = "permit (principal action resource);"
    val result = Cedar.validateSyntax(policy)
    assertEquals(false, result.success)
    assertNotNull(result.errors)
    assertEquals(1, result.errors?.size)
    if (result.errors != null) {
      var errorOffset = result.errors!![0].offset
      var errorLength = result.errors!![0].length
      assertEquals(18, errorOffset)
      assertEquals(6, errorLength)
    }
  }

  @Test
  fun `validate_schema_json_passes`() {
    val schemaJSON = "{\n      \"\" : {\n        \"entityTypes\": {\n          \"User\": {}\n        },\n        \"actions\": {\n          \"view\" : {\n            \"appliesTo\": {\n              \"principalTypes\": [\"User\"],\n              \"resourceTypes\": [\"User\"]\n            }\n          }\n        }\n      }\n    }"
    val result =
      Cedar.validateSchemaJSON(schemaJSON)
    assertEquals(true, result.success)
  }

  @Test
  fun `validate_schema_json_translate_passes`() {
    val schemaJSON = "{\n      \"\" : {\n        \"entityTypes\": {\n          \"User\": {}\n        },\n        \"actions\": {\n          \"view\" : {\n            \"appliesTo\": {\n              \"principalTypes\": [\"User\"],\n              \"resourceTypes\": [\"User\"]\n            }\n          }\n        }\n      }\n    }"
    val schemaCedar = "entity User;\n\naction \"view\" appliesTo {\n  principal: [User],\n  resource: [User],\n  context: {}\n};\n"
    val result =
      Cedar.translateSchemaFromJSON(schemaJSON)
    assertEquals(true, result.success)
    assertEquals(schemaCedar, result.schema)
  }

  @Test
  fun `validate_schema_json_fails`() {
    val schemaJSON = "{\n      \"\" : {\n        \"entityTypes\": {\n          \"User\": {}\n        },\n        \"actions\": {\n          \"view\" : {\n            \"appliesTo\": {\n              \"principalTypes\": [\"Usr\"],\n              \"resourceTypes\": [\"User\"]\n            }\n          }\n        }\n      }\n    }"
    val result =
      Cedar.validateSchemaJSON(schemaJSON)
    assertEquals(false, result.success)
  }

  @Test
  fun `validate_schema_cedar_passes`() {
    val schemaCedar = "namespace Demo {\n  entity User in UserGroup = {\n    \"department\": String,\n    \"jobLevel\": Long,\n  };\n  entity UserGroup;\n}"
    val result =
      Cedar.validateSchemaCedar(schemaCedar)
    assertEquals(true, result.success)
  }

  @Test
  fun `validate_schema_cedar_translate_passes`() {
    val schemaCedar = "namespace Demo {\n  entity User in UserGroup = {\n    \"department\": String,\n    \"jobLevel\": Long,\n  };\n  entity UserGroup;\n}"
    val result =
      Cedar.translateSchemaToJSON(schemaCedar)
    assertEquals(true, result.success)
  }

  @Test
  fun `validate_policy_fails`() {
    val schemaJSON = "{ \"entityTypes\": [], \"actions\": [] }"
    val policy = "permit(principal, action, resource);"
    val result = Cedar.validatePolicySchemaJSON(
      schemaJSON,
      policy
    )
    assertEquals(false, result.success)
  }

  @Test
  fun `validate_policy_passes`() {
    val schemaJSON = "{\n      \"\" : {\n        \"entityTypes\": {\n          \"User\": {}\n        },\n        \"actions\": {\n          \"view\" : {\n            \"appliesTo\": {\n              \"principalTypes\": [\"User\"],\n              \"resourceTypes\": [\"User\"]\n            }\n          }\n        }\n      }\n    }"
    val policy = "permit(principal == User::\"Kevin\", action == Action::\"view\", resource == User::\"Kevin\");"
    val result = Cedar.validatePolicySchemaJSON(
      schemaJSON,
      policy
    )
    assertEquals(true, result.success)
  }

  @Test
  fun `validate_entity_passes`() {
    val schemaJSON = "{\n      \"\" : {\n        \"entityTypes\": {\n          \"User\": {}\n        },\n        \"actions\": {}\n      }\n    }"
    val entities = "[\n      {\n        \"uid\": { \"type\": \"User\", \"id\": \"JaneDoe\" },\n        \"parents\": [],\n        \"attrs\": {}\n      }\n    ]"
    val result =
      Cedar.validateEntitiesSchemaJSON(schemaJSON, entities)
    assertEquals(true, result.success)
  }

  @Test
  fun `validate_entity_fails`() {
    val schemaJSON = "{\n      \"\" : {\n        \"entityTypes\": {\n          \"User\": {}\n        },\n        \"actions\": {}\n      }\n    }"
    val entities = "[\n      {\n        \"uid\": \"User::\\\"JaneDoe\\\"\",\n        \"parents\": [],\n        \"attrs\": {}\n      }\n    ]"
    val result =
      Cedar.validateEntitiesSchemaJSON(schemaJSON, entities)
    assertEquals(false, result.success)
    assertEquals("error during entity deserialization: in uid field of <unknown entity>, expected a literal entity reference, but got `\"User::\\\"JaneDoe\\\"\"`", result.errors?.get(0)?.message)
  }

  @Test
  fun `validate_entity_cedar_passes`() {
    val schema = "entity User;"
    val entities = "[\n      {\n        \"uid\": { \"type\": \"User\", \"id\": \"JaneDoe\" },\n        \"parents\": [],\n        \"attrs\": {}\n      }\n    ]"
    val result =
      Cedar.validateEntitiesSchemaCedar(schema, entities)
    assertEquals(true, result.success)
  }

  @Test
  fun `validate_entity_attrs`() {
    val schemaJSON = "{\n      \"\" : {\n        \"entityTypes\": {\n          \"User\": {}\n        },\n        \"actions\": {}\n      }\n    }"
    val entities = "[\n      {\n        \"uid\": { \"type\": \"User\", \"id\": \"JaneDoe\" },\n        \"parents\": [],\n        \"attrs\": {\n          \"tags\": [\"private\"]\n        }\n      }\n    ]"
    val result =
      Cedar.validateEntitiesSchemaJSON(schemaJSON, entities)
    assertEquals(false, result.success)
    assertEquals("error during entity deserialization: attribute `tags` on `User::\"JaneDoe\"` should not exist according to the schema", result.errors?.get(0)?.message)
  }

  @Test
  fun `validate_policy_bad_extensions`() {
    val schemaJSON = "{\n  \"unittest\": {\n    \"entityTypes\": {\n      \"User\": {}\n    },\n    \"actions\": {\n      \"doAction\" : {\n        \"appliesTo\": {\n          \"context\": {\n            \"type\": \"Record\",\n            \"attributes\": {\n              \"userIP\": {\n                \"type\": \"String\"\n              }\n            }\n          },\n          \"principalTypes\": [\"User\"],\n          \"resourceTypes\": [\"User\"]\n        }\n      }\n    }\n  }\n}"
    val policy = "forbid (principal, action, resource)\nwhen {\n    ip(\"ab.ab.ab.ab\").isLoopback() ||\n    ip(context.usrIP).isLoopback() ||\n    decimal(\"0.12345\").lessThan(decimal(\"0.1234\"))\n};"
    val result = Cedar.validatePolicySchemaJSON(
      schemaJSON,
      policy
    )
    assertEquals(false, result.success)
    assertEquals(4, result.errors?.size)
  }

  @Test
  fun `validate_policy_if_then_else`() {
    val schemaJSON = "{\n    \"My::Name::Space\": {\n      \"entityTypes\": {\n        \"User\": {\n          \"memberOfTypes\": [\"UserGroup\"],\n          \"shape\": {\n            \"type\": \"Record\",\n            \"attributes\": {\n              \"department\": { \"type\": \"String\" },\n              \"jobLevel\": {\n                \"type\": \"Long\"\n              }\n            }\n          }\n        },\n        \"UserGroup\": {},\n        \"Photo\": {\n          \"memberOfTypes\": [\"Album\"],\n          \"shape\": {\n            \"type\": \"Record\",\n            \"attributes\": {\n              \"private\": { \"type\": \"Boolean\" },\n              \"account\": {\n                \"type\": \"Entity\",\n                \"name\": \"Account\"\n              }\n            }\n          }\n        },\n        \"Album\": {\n          \"memberOfTypes\": [\"Album\"],\n          \"shape\": {\n            \"type\": \"Record\",\n            \"attributes\": {\n              \"private\": { \"type\": \"Boolean\" },\n              \"account\": {\n                \"type\": \"Entity\",\n                \"name\": \"Account\"\n              }\n            }\n          }\n        },\n        \"Account\": {\n          \"memberOfTypes\": [],\n          \"shape\": {\n            \"type\": \"Record\",\n            \"attributes\": {\n              \"owner\": {\n                \"type\": \"Entity\",\n  \n                \"name\": \"User\"\n              },\n              \"admins\": {\n                \"required\": false,\n                \"type\": \"Set\",\n                \"element\": {\n                  \"type\": \"Entity\",\n                  \"name\": \"User\"\n                }\n              }\n            }\n          }\n        }\n      },\n      \"actions\": {\n        \"photoAction\": {\n          \"appliesTo\": {\n            \"principalTypes\": [],\n            \"resourceTypes\": [],\n            \"context\": {\n              \"type\": \"Record\",\n              \"attributes\": {}\n            }\n          }\n        },\n        \"viewPhoto\": {\n          \"appliesTo\": {\n            \"principalTypes\": [\"User\"],\n            \"resourceTypes\": [\"Photo\"],\n            \"context\": {\n              \"type\": \"Record\",\n              \"attributes\": {\n                \"authenticated\": { \"type\": \"Boolean\" }\n              }\n            }\n          }\n        },\n        \"listAlbums\": {\n          \"appliesTo\": {\n            \"principalTypes\": [\"User\"],\n            \"resourceTypes\": [\"Account\"],\n            \"context\": {\n              \"type\": \"Record\",\n              \"attributes\": {\n                \"authenticated\": {\n                  \"type\": \"Boolean\"\n                }\n              }\n            }\n          }\n        },\n        \"uploadPhoto\": {\n          \"appliesTo\": {\n            \"principalTypes\": [\"User\"],\n            \"resourceTypes\": [\"Album\"],\n            \"context\": {\n              \"type\": \"Record\",\n              \"attributes\": {\n                \"authenticated\": { \"type\": \"Boolean\" },\n                \"photo\": {\n                  \"type\": \"Record\",\n                  \"attributes\": {\n                    \"file_size\": { \"type\": \"Long\" },\n                    \"file_type\": {\n                      \"type\": \"String\"\n                    }\n                  }\n                }\n              }\n            }\n          }\n        }\n      }\n    }\n  }"
    val policy = "permit (principal, action, resource)\nwhen {\n  if context.tier == \"free\" then resource.sizeInBytes < 5000000 else resource.sizeInBytes < 15000000\n};"
    val result = Cedar.validatePolicySchemaJSON(
      schemaJSON,
      policy
    )
    assertEquals(false, result.success)
    assertEquals(9, result.errors?.size)
  }
}
