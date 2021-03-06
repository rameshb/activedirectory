// Copyright 2013 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.ad;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

import javax.naming.directory.*;

/** Test cases for {@link AdEntity}. */
public class AdEntityTest {
  @Test
  public void testStandardConstructor() throws Exception {
    Attributes attrs = new BasicAttributes();
    attrs.put("objectGUID;binary",
        AdServerTest.hexStringToByteArray("000102030405060708090a0b0c"));
    attrs.put("objectSid;binary", // S-1-0-0
        AdServerTest.hexStringToByteArray("010100000000000000000000"));
    attrs.put("uSNChanged", "12345678");
    attrs.put("primaryGroupId", "users");
    attrs.put("userAccountControl", "512");  // standard, enabled, user

    SearchResult sr = new SearchResult("SR name", attrs, attrs);
    sr.setNameInNamespace("cn=user,ou=Users,dc=example,dc=com");
    AdEntity adEntity = new AdEntity(sr);
    assertEquals("user", adEntity.getCommonName());
    assertEquals("S-1-0-0", adEntity.getSid());
    assertEquals("cn=user,ou=Users,dc=example,dc=com", adEntity.getDn());
    assertFalse(adEntity.isWellKnown());
    assertEquals(0, adEntity.getMembers().size());
    assertEquals("S-1-0-users", adEntity.getPrimaryGroupSid());
    assertFalse(adEntity.isDisabled());
  }

  @Test
  public void testWellKnownConstructor() throws Exception {
    AdEntity adEntity = new AdEntity("S-1-1-1",
        "dn=escaped\\,cn=users,ou=Users,dc=example,dc=com");
    adEntity.setUserAccountControl(514);  // disabled user
    assertEquals("escaped,cn=users", adEntity.getCommonName());
    assertEquals("S-1-1-1", adEntity.getSid());
    assertEquals("dn=escaped\\,cn=users,ou=Users,dc=example,dc=com",
        adEntity.getDn());
    assertTrue(adEntity.isWellKnown());
    assertEquals(0, adEntity.getMembers().size());
    assertTrue(adEntity.isDisabled());
  }

  @Test
  public void testEquals() throws Exception {
    AdEntity one = one = new AdEntity("foo", "bar");
    String nonAdEntity = new String("bogus");
    assertFalse(one.equals(nonAdEntity));
    AdEntity two = new AdEntity("foo", "baz");
    assertFalse(one.equals(nonAdEntity));
    two = new AdEntity("baz", "bar");
    assertFalse(one.equals(nonAdEntity));

    Attributes attrs = new BasicAttributes();
    attrs.put("objectGUID;binary",
        AdServerTest.hexStringToByteArray("000102030405060708090a0b0c"));
    attrs.put("objectSid;binary", // S-1-0-0
        AdServerTest.hexStringToByteArray("010100000000000000000000"));
    attrs.put("uSNChanged", "12345678");
    attrs.put("primaryGroupId", "users");
    attrs.put("userPrincipalName", "user");
    attrs.put("sAMAccountName", "sam");
    attrs.put("userAccountControl", "512");  // standard, enabled, user

    SearchResult sr = new SearchResult("SR name", attrs, attrs);
    sr.setNameInNamespace("cn=user,ou=Users,dc=example,dc=com");
    one = new AdEntity(sr);
    attrs.put("primaryGroupId", "another group");
    sr = new SearchResult("SR name", attrs, attrs);
    sr.setNameInNamespace("cn=user,ou=Users,dc=example,dc=com");
    two = new AdEntity(sr);
    assertFalse(one.equals(two));
    // TODO(myk): additional equality tests for other fields, if deemed useful

    // test userAccountControl field for equality
    one = new AdEntity("dn1", "dn=user,ou=Users,dc=example,dc=com");
    two = new AdEntity("dn1", "dn=user,ou=Users,dc=example,dc=com");
    assertEquals(one, two);
    two.setUserAccountControl(514);  // disabled user
    assertFalse(one.equals(two));
    two.setUserAccountControl(0);
    assertEquals(one, two);
  }

  @Test
  public void testWellKnownConstructorNoCommaInDN() throws Exception {
    AdEntity adEntity = new AdEntity("NoComma", "dc=com");
    assertEquals("com", adEntity.getCommonName());
    assertEquals("dc=com", adEntity.getDn());
    assertTrue(adEntity.isWellKnown());
  }

  @Test
  public void testWellKnownConstructorTrailingComma() throws Exception {
    AdEntity adEntity = new AdEntity("NoComma", "dc=com,");
    assertEquals("com", adEntity.getCommonName());
    assertEquals("dc=com,", adEntity.getDn());
    assertTrue(adEntity.isWellKnown());
  }

  @Test
  public void testAppendGroupsOnEmptyGroup() throws Exception {
    AdEntity adEntity = new AdEntity("parentGroup", "dc=com");

    Attributes attrs = new BasicAttributes();
    attrs.put("objectGUID;binary",
        AdServerTest.hexStringToByteArray("000102030405060708090a0b0c"));
    attrs.put("objectSid;binary", // S-1-0-0
        AdServerTest.hexStringToByteArray("010100000000000000000000"));
    attrs.put("member", null);
    Attribute memberAttr = attrs.get("member");
    memberAttr.clear();
    SearchResult sr = new SearchResult("subgroup", attrs, attrs);
    sr.setNameInNamespace("cn=subgroup,ou=Groups,dc=example,dc=com");
    AdEntity ae = new AdEntity(sr);

    HashSet<String> expectedMembers = new HashSet<String>();
    assertEquals(expectedMembers, ae.getMembers());
    assertEquals(0, adEntity.appendGroups(sr));
  }

  @Test
  public void testAppendGroupsOnRealGroup() throws Exception {
    AdEntity adEntity = new AdEntity("parentGroup", "dc=com");

    Attributes attrs = new BasicAttributes();
    attrs.put("objectGUID;binary",
        AdServerTest.hexStringToByteArray("000102030405060708090a0b0c"));
    attrs.put("objectSid;binary", // S-1-0-0
        AdServerTest.hexStringToByteArray("010100000000000000000000"));
    List<String> members = Arrays.asList("dn_for_user_1", "dn_for_user_2");
    attrs.put("member", null);
    Attribute memberAttr = attrs.get("member");
    memberAttr.clear();
    for (String member: members) {
      memberAttr.add(member);
    }

    SearchResult sr = new SearchResult("subgroup", attrs, attrs);
    sr.setNameInNamespace("cn=subgroup,ou=Groups,dc=example,dc=com");
    AdEntity ae = new AdEntity(sr);

    assertEquals(new HashSet<String>(members), ae.getMembers());
    assertEquals(2, adEntity.appendGroups(sr));
  }

  @Test
  public void testParseForeignSecurityPrincipal() throws Exception {
    AdEntity adEntity = new AdEntity("NoComma", "dc=com");
    assertNull(adEntity.parseForeignSecurityPrincipal(""));
    assertNull(adEntity.parseForeignSecurityPrincipal(
        "cn=foreignsecurityprincipals,dc=example,dc=com"));
    String validSid = "S-1-5-21-42";
    assertEquals(validSid, adEntity.parseForeignSecurityPrincipal(
        "id=" + validSid + ",cn=foreignsecurityprincipals,dc=example,dc=com"));
  }
}
