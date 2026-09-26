// Copyright Cedar Contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.thefellow.cedar.core

import io.github.thefellow.cedar.testing.FsWorkspace
import io.github.thefellow.cedar.wasm.Cedar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/* Port of upstream src/test/suite/validation.test.ts. */

@Suppress("ClassName", "UNNECESSARY_SAFE_CALL", "VARIABLE_WITH_REDUNDANT_INITIALIZER", "CanBeVal")
class ValidationSuiteTest {
  private fun readTestDataFile(dirname: String, filename: String): String =
    File(File(FsWorkspace.testdata, dirname), filename).readText()

  // suite: Validation Schema Cedar Test Suite

  @Test
  fun `validate shadow warnings`() {
    val schema = readTestDataFile("shadow", "Demo.cedarschema")
    val result =
      Cedar.validateSchemaCedar(schema)
    assertEquals(true, result.success)
    // The name `ipaddr` shadows a builtin Cedar name. You'll have to refer to the builtin as `__cedar::ipaddr`.
    // The name `String` shadows a builtin Cedar name. You'll have to refer to the builtin as `__cedar::String`.
    assertEquals(2, result.warnings?.size)
  }

  @Test
  fun `validate __cedar reserved`() {
    val schema = readTestDataFile("reserved", "__cedar.cedarschema")
    val result =
      Cedar.validateSchemaCedar(schema)
    assertEquals(false, result.success)
    // error parsing schema: use of the reserved `__cedar` namespace
    assertEquals(1, result.errors?.size)
    if (result.errors != null) {
      var e = result.errors!![0]
      assertTrue(e.message.contains("use of the reserved `__cedar` namespace"))
      assertEquals(180, e.offset)
      assertEquals(7, e.length)
    }
  }

  @Test
  fun `validate Set reserved`() {
    val schema = readTestDataFile("reserved", "set.cedarschema")
    val result =
      Cedar.validateSchemaCedar(schema)
    assertEquals(false, result.success)
    // error parsing schema: this uses a reserved schema keyword: `Set`
    assertEquals(1, result.errors?.size)
    if (result.errors != null) {
      var e = result.errors!![0]
      assertTrue(e.message.contains("this uses a reserved schema keyword: `Set`"))
      assertEquals(188, e.offset)
      assertEquals(3, e.length)
    }
  }

  // suite: Validation RegEx Test Suite
  @Test
  fun `validate policy error   found at  `() {
    val policy = readTestDataFile("notfound", "policy.cedar")
    val result = Cedar.validateSyntax(policy)
    assertEquals(false, result.success)
    assertEquals(1, result.errors?.size)

    if (result.errors != null) {
      var e = result.errors!![0]
      assertTrue(e.message.startsWith("unexpected token `action`"))
      assertEquals(18, e.offset)
      assertEquals(6, e.length)
    }
  }

  @Test
  fun `validate policy warning   impossible  `() {
    val policy = readTestDataFile("impossible", "policy.cedar")
    val schema = readTestDataFile("impossible", "cedarschema.json")

    val result = Cedar.validatePolicySchemaJSON(
      schema,
      policy,
    )
    assertEquals(true, result.success)
    assertEquals(1, result.warnings?.size)

    if (result.warnings != null) {
      // for policy `policy0`, policy is impossible: the policy expression evaluates to false for all valid requests
      val e = result.warnings!![0]
      assertTrue(e.message.contains("policy is impossible:"))
    }
  }

  @Test
  fun `validate policy unrecognized entity and action type`() {
    val policy = readTestDataFile("unrecognized", "policy.cedar")
    val schema = readTestDataFile("unrecognized", "cedarschema.json")

    val result = Cedar.validatePolicySchemaJSON(
      schema,
      policy,
    )
    assertEquals(false, result.success)

    if (result.errors != null) {
      assertEquals(2, result.errors!!.size)

      // for policy `policy0`, unable to find an applicable action given the policy scope constraints
      // for policy `policy0`, unrecognized entity type `Tst`\ndid you mean `Test`?
      // for policy `policy0`, unrecognized action `Action::"doTst"`\ndid you mean `Action::"doTest"`?

      var errorMsg = result.errors!![0].message
      assertEquals(21, result.errors!![0].offset)
      assertEquals(3, result.errors!![0].length)

      var found = errorMsg.jsMatch(UNRECOGNIZED_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Tst", found?.group("unrecognized"))
        assertEquals("Test", found?.group("suggestion"))
      }

      errorMsg = result.errors!![1].message
      found = errorMsg.jsMatch(UNRECOGNIZED_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Action::\"doTst\"", found?.group("unrecognized"))
        assertEquals("Action::\"doTest\"", found?.group("suggestion"))
      }
    }
  }

  @Test
  fun `validate policy schema error   at line  `() {
    val schema = readTestDataFile("atline", "cedarschema.json")

    val result = Cedar.validateSchemaJSON(schema)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // JSON Schema file could not be parsed: missing field `entityTypes` at line 2 column 9
      var errorMsg = result.errors!![0].message
      var found = errorMsg.jsMatch(AT_LINE_SCHEMA_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals(2, found?.group("line")!!.toInt())
        assertEquals(9, found?.group("column")!!.toInt())
      }
    }
  }

  @Test
  fun `validate policy schema JSON undeclared entity type`() {
    val schema = readTestDataFile(
      "undeclared",
      "entitytype.cedarschema.json",
    )

    val result = Cedar.validateSchemaJSON(schema)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // failed to resolve types: Test, Test\n`Test` has not been declared as an entity type
      var errorMsg = result.errors!![0].message
      var found = errorMsg.jsMatch(UNDECLARED_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("an entity type", found?.group("type"))
        assertEquals("Test", found?.group("undeclared"))
      }
    }
  }

  @Test
  fun `validate policy schema Cedar undeclared entity type`() {
    val schema = readTestDataFile("undeclared", "entitytype.cedarschema")

    val result =
      Cedar.validateSchemaCedar(schema)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // failed to resolve types: Test, Test\n`Test` has not been declared as an entity type
      var e = result.errors!![0]
      var found = e.message.jsMatch(UNDECLARED_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("an entity type", found?.group("type"))
        assertEquals("Test", found?.group("undeclared"))
      }
      assertEquals(42, e.offset)
      assertEquals(4, e.length)
    }
  }

  @Test
  fun `validate policy schema undeclared actions`() {
    val schema = readTestDataFile("undeclared", "actions.cedarschema.json")

    val result = Cedar.validateSchemaJSON(schema)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // undeclared actions: Action::"test1" and Action::"test2"\nany actions appearing as parents need to be declared as actions
      var errorMsg = result.errors!![0].message
      var found = errorMsg.jsMatch(UNDECLARED_ACTION_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("action", found?.group("type"))
        // Check that both undeclared actions are found in the error message
        assertTrue(errorMsg.contains("Action::\"test1\""))
        assertTrue(errorMsg.contains("Action::\"test2\""))
      }
    }
  }

  @Test
  fun `validate entity expected attributes`() {
    val entities = readTestDataFile(
      "entityattr",
      "expected.cedarentities.json",
    )
    val schema = readTestDataFile("entityattr", "cedarschema.json")

    val result =
      Cedar.validateEntitiesSchemaJSON(schema, entities)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // entity does not conform to the schema: expected entity `Test::"expected"` to have attribute `test`, but it does not
      var errorMsg = result.errors!![0].message
      assertTrue(errorMsg.startsWith("entity does not conform to the schema: "))
      errorMsg = errorMsg.substring(errorMsg.indexOf(": ") + 2)
      assertEquals("expected entity `Test::\"expected\"` to have attribute `test`, but it does not", errorMsg)
      var found = errorMsg.jsMatch(EXPECTED_ATTR_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Test", found?.group("type"))
        assertEquals("expected", found?.group("id"))
        assertEquals("test", found?.group("suggestion"))
      }
    }
  }

  @Test
  fun `validate entity expected nested attributes`() {
    val entities = readTestDataFile(
      "entityattr",
      "expected2.cedarentities.json",
    )
    val schema = readTestDataFile("entityattr", "cedarschema.json")

    val result =
      Cedar.validateEntitiesSchemaJSON(schema, entities)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // error during entity deserialization: in attribute `nested` on `Test::"expected"`, expected the record to have an attribute `test`, but it does not
      var errorMsg = result.errors!![0].message
      assertTrue(errorMsg.startsWith("error during entity deserialization: "))
      errorMsg = errorMsg.substring(errorMsg.indexOf(": ") + 2)
      assertEquals("in attribute `nested` on `Test::\"expected\"`, expected the record to have an attribute `test`, but it does not", errorMsg)
      var found = errorMsg.jsMatch(EXPECTED_ATTR2_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Test", found?.group("type"))
        assertEquals("expected", found?.group("id"))
        assertEquals("nested", found?.group("attribute"))
        assertEquals("test", found?.group("suggestion"))
      }
    }
  }

  @Test
  fun `validate entity mismatch type attributes`() {
    val entities = readTestDataFile(
      "entityattr",
      "mismatch.cedarentities.json",
    )
    val schema = readTestDataFile("entityattr", "cedarschema.json")

    val result =
      Cedar.validateEntitiesSchemaJSON(schema, entities)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // entity does not conform to the schema: in attribute `test` on `Test::"mismatch"`, type mismatch: value was expected to have type string, but it actually has type long: `1`
      var errorMsg = result.errors!![0].message
      assertTrue(errorMsg.startsWith("entity does not conform to the schema: "))
      errorMsg = errorMsg.substring(errorMsg.indexOf(": ") + 2)
      assertEquals("in attribute `test` on `Test::\"mismatch\"`, type mismatch: value was expected to have type string, but it actually has type long: `1`", errorMsg)
      var found = errorMsg.jsMatch(MISMATCH_ATTR_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Test", found?.group("type"))
        assertEquals("mismatch", found?.group("id"))
        assertEquals("test", found?.group("attribute"))
      }
    }
  }

  @Test
  fun `validate entity mismatch entity type attributes`() {
    val entities = readTestDataFile(
      "entityattr",
      "mismatchentity.cedarentities.json",
    )
    val schema = readTestDataFile("entityattr", "cedarschema.json")

    val result =
      Cedar.validateEntitiesSchemaJSON(schema, entities)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // entity does not conform to the schema: in attribute `self` on `Test::"mismatchentity"`, type mismatch: value was expected to have type `Test`, but it actually has type (entity of type `Tst`): `Tst::"mismatchtype"`
      var errorMsg = result.errors!![0].message
      assertTrue(errorMsg.startsWith("entity does not conform to the schema: "))
      errorMsg = errorMsg.substring(errorMsg.indexOf(": ") + 2)
      assertEquals("in attribute `self` on `Test::\"mismatchentity\"`, type mismatch: value was expected to have type `Test`, but it actually has type (entity of type `Tst`): `Tst::\"mismatchtype\"`", errorMsg)
      var found = errorMsg.jsMatch(MISMATCH_ATTR_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Test", found?.group("type"))
        assertEquals("mismatchentity", found?.group("id"))
        assertEquals("self", found?.group("attribute"))
      }
    }
  }

  @Test
  fun `validate entity exist attributes`() {
    val entities = readTestDataFile("entityattr", "exist.cedarentities.json")
    val schema = readTestDataFile("entityattr", "cedarschema.json")

    val result =
      Cedar.validateEntitiesSchemaJSON(schema, entities)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // error during entity deserialization: attribute `tst` on `Test::"exist"` should not exist according to the schema
      var errorMsg = result.errors!![0].message
      assertTrue(errorMsg.startsWith("error during entity deserialization: "))
      errorMsg = errorMsg.substring(errorMsg.indexOf(": ") + 2)
      assertEquals("attribute `tst` on `Test::\"exist\"` should not exist according to the schema", errorMsg)
      var found = errorMsg.jsMatch(EXIST_ATTR_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Test", found?.group("type"))
        assertEquals("exist", found?.group("id"))
        assertEquals("tst", found?.group("attribute"))
      }
    }
  }

  @Test
  fun `validate entity exist type`() {
    val entities = readTestDataFile(
      "entitytype",
      "missingnamespace.cedarentities.json",
    )
    val schema = readTestDataFile("entitytype", "cedarschema.json")

    val result =
      Cedar.validateEntitiesSchemaJSON(schema, entities)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // error during entity deserialization: entity `Employee::"12UA45"` has type `Employee` which is not declared in the schema`?
      var errorMsg = result.errors!![0].message
      assertTrue(errorMsg.startsWith("error during entity deserialization: "))
      errorMsg = errorMsg.substring(errorMsg.indexOf(": ") + 2)
      assertEquals("entity `Employee::\"12UA45\"` has type `Employee` which is not declared in the schema", errorMsg)
      var found = errorMsg.jsMatch(NOTDECLARED_TYPE_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("Employee", found?.group("type"))
        assertEquals("12UA45", found?.group("id"))
      }
    }
  }

  @Test
  fun `validate entity parent type`() {
    val entities = readTestDataFile(
      "entitytype",
      "notallowedparent.cedarentities.json",
    )
    val schema = readTestDataFile("entitytype", "cedarschema.json")

    val result =
      Cedar.validateEntitiesSchemaJSON(schema, entities)
    assertEquals(false, result.success)

    if (result.errors != null) {
      // entity does not conform to the schema: `XYZCorp::Employee::"12UA45"` is not allowed to have an ancestor of type `XYZCorp::Employee` according to the schema
      var errorMsg = result.errors!![0].message
      assertTrue(errorMsg.startsWith("entity does not conform to the schema: "))
      errorMsg = errorMsg.substring(errorMsg.indexOf(": ") + 2)
      assertEquals("`XYZCorp::Employee::\"12UA45\"` is not allowed to have an ancestor of type `XYZCorp::Employee` according to the schema", errorMsg)
      var found = errorMsg.jsMatch(NOTALLOWED_PARENT_REGEX)
      assertNotNull(found)
      if (found != null) {
        assertEquals("XYZCorp::Employee", found?.group("type"))
        assertEquals("12UA45", found?.group("id"))
      }
    }
  }

  // suite: Validate Policy Entities Test Suite
  private fun fetchEntityTypes(head: String): EntityTypes {
    val schemaDoc = FsWorkspace.testdataDocument("narrow", "cedarschema.json")

    val principalTypes = determineEntityTypes(schemaDoc, "principal", head)
    val resourceTypes = determineEntityTypes(schemaDoc, "resource", head)
    val actionIds = determineEntityTypes(schemaDoc, "action", head)

    return EntityTypes(
      principals = principalTypes,
      resources = resourceTypes,
      actions = actionIds,
    )
  }

  @Test
  fun `validate unspecified`() {
    val head = "permit(principal, action, resource)"
    val e = fetchEntityTypes(head)

    assertEquals(2, e.principals.size)
    assertEquals("NS::E1", e.principals[0])
    assertEquals("NS::E2", e.principals[1])

    assertEquals(3, e.actions.size)
    assertEquals("NS::Action::\"a\"", e.actions[0])
    assertEquals("NS::Action::\"a1\"", e.actions[1])
    assertEquals("NS::Action::\"a2\"", e.actions[2])

    assertEquals(2, e.resources.size)
    assertEquals("NS::R1", e.resources[0])
    assertEquals("NS::R2", e.resources[1])
  }

  @Test
  fun `validate E1 a1 R1`() {
    val head = "permit (principal in NS::E::\"id\", action == NS::Action::\"a1\", resource)"
    val e = fetchEntityTypes(head)

    assertEquals(1, e.principals.size)
    assertEquals("NS::E1", e.principals[0])

    assertEquals(1, e.actions.size)
    assertEquals("NS::Action::\"a1\"", e.actions[0])

    assertEquals(1, e.resources.size)
    assertEquals("NS::R1", e.resources[0])
  }
}
