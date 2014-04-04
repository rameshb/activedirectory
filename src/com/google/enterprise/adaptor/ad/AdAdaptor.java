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

import com.google.common.annotations.VisibleForTesting;
import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.GroupPrincipal;
import com.google.enterprise.adaptor.PollingIncrementalLister;
import com.google.enterprise.adaptor.Principal;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;
import com.google.enterprise.adaptor.UserPrincipal;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InterruptedNamingException;
import javax.naming.NamingException;

/** Adaptor for Active Directory. */
public class AdAdaptor extends AbstractAdaptor
    implements PollingIncrementalLister {
  private static final Logger log
      = Logger.getLogger(AdAdaptor.class.getName());
  private static final boolean CASE_SENSITIVITY = false;
  /**
   * Only one crawl (full or incremental) is done at a time, however:
   * when a full crawl is invoked, we wait until the lock is available;
   * when an incremental crawl is invoked, we immediately return if the lock
   * isn't available.
   */
  private final ReentrantLock mutex = new ReentrantLock();

  private String namespace;
  private String defaultUser;  // used if an AD doesn't override
  private String defaultPassword;  // likewise
  private List<AdServer> servers = new ArrayList<AdServer>();
  private Map<String, String> localizedStrings;
  private boolean feedBuiltinGroups;
  private GroupCatalog lastCompleteGroupCatalog = null;
  private String ldapTimeoutInMillis;

  @Override
  public void initConfig(Config config) {
    config.addKey("ad.servers", null);
    config.addKey("adaptor.namespace", "Default");
    config.addKey("ad.defaultUser", "");
    config.addKey("ad.defaultPassword", "");
    config.addKey("ad.localized.Everyone", "Everyone");
    config.addKey("ad.localized.NTAuthority", "NT Authority");
    config.addKey("ad.localized.Interactive", "Interactive");
    config.addKey("ad.localized.AuthenticatedUsers", "Authenticated Users");
    config.addKey("ad.localized.Builtin", "BUILTIN");
    config.addKey("ad.feedBuiltinGroups", "false");
    config.addKey("ad.ldapReadTimeoutSecs", "90");
  }

  @Override
  public void init(AdaptorContext context) throws Exception {
    Config config = context.getConfig();
    namespace = config.getValue("adaptor.namespace");
    log.config("common namespace: " + namespace);
    defaultUser = config.getValue("ad.defaultUser");
    defaultPassword = context.getSensitiveValueDecoder().decodeValue(
        config.getValue("ad.defaultPassword"));
    feedBuiltinGroups = Boolean.parseBoolean(
        config.getValue("ad.feedBuiltinGroups"));
    ldapTimeoutInMillis = parseLdapTimeoutInMillis(
        config.getValue("ad.ldapReadTimeoutSecs"));
    // register for incremental pushes
    context.setPollingIncrementalLister(this);
    List<Map<String, String>> serverConfigs
        = config.getListOfConfigs("ad.servers");
    servers.clear();  // in case init gets called again
    for (Map<String, String> singleServerConfig : serverConfigs) {
      String host = singleServerConfig.get("host");
      int port = 389;
      if (singleServerConfig.containsKey("port")) {
        port = Integer.parseInt(singleServerConfig.get("port"));
      }
      Method method = Method.STANDARD;
      if (singleServerConfig.containsKey("method")) {
        String methodStr = singleServerConfig.get("method").toLowerCase();
        if ("ssl".equals(methodStr)) {
          method = Method.SSL;
        } else if (!"standard".equals(methodStr)) {
          throw new IllegalArgumentException("invalid method: " + methodStr);
        }
      }
      String principal = singleServerConfig.get("user");
      if (null == principal) {
        principal = defaultUser;
      }
      if (principal.isEmpty()) {
        throw new IllegalStateException("user not specified for host " + host);
      }
      String passwd = context.getSensitiveValueDecoder().decodeValue(
          singleServerConfig.get("password"));
      if (null == passwd) {
        passwd = defaultPassword;
      }
      if (passwd.isEmpty()) {
        throw new IllegalStateException("password not specified for host "
            + host);
      }
      AdServer adServer = newAdServer(method, host, port, principal, passwd,
          ldapTimeoutInMillis);
      adServer.initialize();
      servers.add(adServer);
      Map<String, String> dup = new TreeMap<String, String>(singleServerConfig);
      dup.put("password", "XXXXXX");  // hide password
      log.log(Level.CONFIG, "AD server spec: {0}", dup);
    }
    localizedStrings = config.getValuesWithPrefix("ad.localized.");
  }

  /**
   * This method exists specifically to be overwritten in the test class, in
   * order to inject a version of AdServer that uses mocks.
   */
  @VisibleForTesting
  AdServer newAdServer(Method method, String host, int port,
      String principal, String passwd, String ldapTimeoutInMillis) {
    return new AdServer(method, host, port, principal, passwd,
        ldapTimeoutInMillis);
  }

  private static String parseLdapTimeoutInMillis(String timeInSeconds) {
    if (timeInSeconds.equals("0") || timeInSeconds.trim().equals("")) {
      timeInSeconds = "90";
      log.log(Level.CONFIG, "ad.ldapReadTimeoutSecs set to default of 90 sec.");
    }
    try {
      return String.valueOf(1000L * Integer.parseInt(timeInSeconds));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("invalid ad.ldapReadTimeoutSecs: " +
          timeInSeconds);
    }
  }

  /** This adaptor does not serve documents. */
  @Override
  public void getDocContent(Request req, Response resp) throws IOException {
    resp.respondNotFound();
  }

  /** Call default main for adaptors. */
  public static void main(String[] args) {
    AbstractAdaptor.main(new AdAdaptor(), args);
  }

  /** Crawls/pushes all groups from all AdServers. */

  @Override
  public void getDocIds(DocIdPusher pusher) throws InterruptedException,
      IOException {
    log.log(Level.FINER, "getDocIds invoked - waiting for lock.");
    mutex.lock();
    try {
      clearLastCompleteGroupCatalog();
      GroupCatalog cumulativeCatalog = makeFullCatalog();
      // all servers were able to successfully populate the catalog: do a push
      // TODO(myk): Rework the structure so that a member variable of
      // cumulativeCatalog isn't passed in as a parameter to its own method.
      cumulativeCatalog.resolveForeignSecurityPrincipals(
          cumulativeCatalog.entities);
      Map<GroupPrincipal, List<Principal>> groups =
          cumulativeCatalog.makeDefs(cumulativeCatalog.entities);
      pusher.pushGroupDefinitions(groups, CASE_SENSITIVITY);
      cumulativeCatalog.members.clear();
      lastCompleteGroupCatalog = cumulativeCatalog;
    } finally {
      mutex.unlock();
      log.log(Level.FINE, "getDocIds ending - lock released.");
    }
  }

  @VisibleForTesting
  GroupCatalog makeFullCatalog() throws InterruptedException, IOException {
    GroupCatalog cumulativeCatalog = new GroupCatalog(localizedStrings,
        namespace, feedBuiltinGroups);
    for (AdServer server : servers) {
      try {
        server.ensureConnectionIsCurrent();
        GroupCatalog catalog = new GroupCatalog(localizedStrings, namespace,
              feedBuiltinGroups);
        catalog.readEverythingFrom(server, /*includeMembers=*/ true);
        cumulativeCatalog.add(catalog);
      } catch (NamingException ne) {
        String host = server.getHostName();
        throw new IOException("could not get entities from " + host, ne);
      }
    }
    return cumulativeCatalog;
  }

  /**
   * Attempts an incremental push of updated groups from all AdServers.
   * <p>
   * When a server cannot do an incremental push, it does a full crawl without
   * doing a push afterwards -- this sets up its state so that subsequent
   * incremental pushes can work.
   */
  @Override
  public void getModifiedDocIds(DocIdPusher pusher) throws InterruptedException,
      IOException {
    if (!mutex.tryLock()) {
      log.log(Level.FINE, "getModifiedDocIds could not acquire lock; " +
          "will retry later.");
      return;
    }
    try {
      log.log(Level.FINE, "getModifiedDocIds starting - acquired lock.");
      getModifiedDocIdsHelper(pusher);
    } finally {
      mutex.unlock();
      log.log(Level.FINE, "getModifiedDocIds ending - lock released.");
    }
  }

  @VisibleForTesting
  void getModifiedDocIdsHelper(DocIdPusher pusher) throws InterruptedException,
      IOException {
    if (lastCompleteGroupCatalog == null) {
      log.log(Level.FINE, "getModifiedDocIds doing a fetch with no push.");
      lastCompleteGroupCatalog = makeFullCatalog();
      return;
    }

    Set<AdEntity> allNewOrUpdatedEntities = new HashSet<AdEntity>();
    for (AdServer server : servers) {
      String previousServiceName = server.getDsServiceName();
      String previousInvocationId = server.getInvocationID();
      long previousHighestUSN = server.getHighestCommittedUSN();
      try {
        server.ensureConnectionIsCurrent();
        allNewOrUpdatedEntities.addAll(
            lastCompleteGroupCatalog.readUpdatesFrom(server,
                previousServiceName, previousInvocationId,
                previousHighestUSN));
      } catch (NamingException ne) {
        // invalidate the saved group catalog
        clearLastCompleteGroupCatalog();
        String host = server.getHostName();
        throw new IOException("could not get entities from " + host, ne);
      }
    }

    // all servers were able to successfully update the catalog: do a push
    lastCompleteGroupCatalog.resolveForeignSecurityPrincipals(
        allNewOrUpdatedEntities);
    Map<GroupPrincipal, List<Principal>> groups =
        lastCompleteGroupCatalog.makeDefs(allNewOrUpdatedEntities);
    pusher.pushGroupDefinitions(groups, CASE_SENSITIVITY);
    lastCompleteGroupCatalog.members.clear();
  }

  // don't expose the <code>lastCompleteGroupCatalog</code> field, but do allow
  // tests to clear it
  @VisibleForTesting
  void clearLastCompleteGroupCatalog() {
    lastCompleteGroupCatalog = null;
  }

  // Space for all group info, organized in different ways
  @VisibleForTesting
  static class GroupCatalog {
    Map<String, String> localizedStrings;
    String namespace;
    boolean feedBuiltinGroups;
    Set<AdEntity> entities = new HashSet<AdEntity>();
    Map<AdEntity, Set<String>> members = new HashMap<AdEntity, Set<String>>();

    Map<String, AdEntity> bySid = new HashMap<String, AdEntity>();
    Map<String, AdEntity> byDn = new HashMap<String, AdEntity>();
    Map<AdEntity, String> domain = new HashMap<AdEntity, String>();

    final AdEntity everyone;
    final AdEntity interactive;
    final AdEntity authenticatedUsers;
    final Map<AdEntity, Set<String>> wellKnownMembership;

    public GroupCatalog(Map<String, String> localizedStrings, String namespace,
        boolean feedBuiltinGroups) {
      this.localizedStrings = localizedStrings;
      this.namespace = namespace;
      this.feedBuiltinGroups = feedBuiltinGroups;
      everyone = new AdEntity("S-1-1-0",
          MessageFormat.format("CN={0}",
          localizedStrings.get("Everyone")));
      interactive = new AdEntity("S-1-5-4",
          MessageFormat.format("CN={0},DC={1}",
          localizedStrings.get("Interactive"),
          localizedStrings.get("NTAuthority")));
      authenticatedUsers = new AdEntity("S-1-5-11" ,
          MessageFormat.format("CN={0},DC={1}",
          localizedStrings.get("AuthenticatedUsers"),
          localizedStrings.get("NTAuthority")));
      wellKnownMembership = new HashMap<AdEntity, Set<String>>();
      wellKnownMembership.put(everyone, new TreeSet<String>());
      wellKnownMembership.put(interactive, new TreeSet<String>());
      wellKnownMembership.put(authenticatedUsers, new TreeSet<String>());

      // To save space on GSA onboard groups database, we add "everyone" as a
      // member to "Interactive" and "authenticated users" groups.
      // Each user from domain will be added as member of "everyone" group
      // and user will be indirect member for
      // "Interactive" and "authenticated users" groups.
      wellKnownMembership.get(interactive).add(everyone.getDn());
      wellKnownMembership.get(authenticatedUsers).add(everyone.getDn());

      entities.add(everyone);
      entities.add(interactive);
      entities.add(authenticatedUsers);

      bySid.put(everyone.getSid(), everyone);
      byDn.put(everyone.getDn(), everyone);

      bySid.put(interactive.getSid(), interactive);
      byDn.put(interactive.getDn(), interactive);
      domain.put(interactive, localizedStrings.get("NTAuthority"));

      bySid.put(authenticatedUsers.getSid(), authenticatedUsers);
      byDn.put(authenticatedUsers.getDn(), authenticatedUsers);
      domain.put(authenticatedUsers, localizedStrings.get("NTAuthority"));
    }

    @VisibleForTesting
    GroupCatalog(Map<String, String> localizedStrings, String namespace,
        boolean feedBuiltinGroups, Set<AdEntity> entities,
        Map<AdEntity, Set<String>> members,
        Map<String, AdEntity> bySid,
        Map<String, AdEntity> byDn,
        Map<AdEntity, String> domain) {
      this(localizedStrings, namespace, feedBuiltinGroups);
      this.entities.clear();
      this.entities.addAll(entities);
      this.members.putAll(members);
      this.bySid.putAll(bySid);
      this.byDn.putAll(byDn);
      this.domain.putAll(domain);
    }

    @VisibleForTesting
    void readEverythingFrom(AdServer server, boolean includeMembers)
        throws InterruptedNamingException {
      final String[] nonMemberAttributes = new String[] { "uSNChanged",
          "sAMAccountName", "objectGUID;binary", "objectSid;binary",
          "userPrincipalName", "primaryGroupId", "userAccountControl" };
      final String[] allAttributes = Arrays.copyOf(nonMemberAttributes,
          nonMemberAttributes.length + 1);
      allAttributes[nonMemberAttributes.length] = "member";
      log.log(Level.FINE, "Starting full crawl.");
      // LDAP_MATCHING_RULE_BIT_AND = 1.2.840.113556.1.4.803
      // and ADS_GROUP_TYPE_SECURITY_ENABLED = 2147483648.
      entities = server.search("(|(&(objectClass=group)"
          + "(groupType:1.2.840.113556.1.4.803:=2147483648))"
          + "(&(objectClass=user)(objectCategory=person)))",
          /*deleted=*/ false,
          includeMembers ? allAttributes : nonMemberAttributes);
      // disabled groups handled later, in makeDefs()
      log.log(Level.FINE, "Ending full crawl - now starting processing.");
      processEntities(entities, server.getnETBIOSName());
    }

    /**
     * Do an AD search for only groups/users that have been updated since the
     * previous full or incremental search.
     * <p>If either <code>getDsServiceName()</code> or
     * <code>server.getInvocationID()</code> have changed, the cache is stale
     * and (only) a full crawl is done, to refresh the cache.  If neither have
     * changed, then only groups/users that have a <code>uSNChanged</code>
     * attribute newer than the <code>previousHighestUSN</code> parameter are
     * retrieved and returned.
     * @param server the Active Directory server to query
     * @param previousServiceName last-crawled value of
     *     <code>getDsServiceName()</code>
     * @param previousInvocationId last-crawled value of
     *     <code>server.getInvocationID()</code>
     * @param previousHighestUSN last-crawled value of
     *     <code>server.getHighestCommittedUSN()</code>
     * <code>previousHighestUSN</code>.
     *
     * @return all instances of <code>AdEntity</code> that are users/groups that
     *     have a <code>uSNChanged</code> attribute newer than, or
     *     <code>Collections.emptySet()</code> when the cache had been stale.
     */
    @VisibleForTesting
    Set<AdEntity> readUpdatesFrom(AdServer server, String previousServiceName,
        String previousInvocationId, long previousHighestUSN)
        throws InterruptedNamingException {
      // TODO(myk): Determine whether adaptors should include code to get/set
      // last full sync time, and if exceeding some threshhold should force a
      // full crawl.
      String currentServiceName = server.getDsServiceName();
      String currentInvocationId = server.getInvocationID();
      long currentHighestUSN = server.getHighestCommittedUSN();
      if (!currentServiceName.equals(previousServiceName)) {
        // only log a warning if previous service name was set to something
        if (previousServiceName != null) {
          log.log(Level.WARNING, "Directory Controller changed from {0} to {1} "
              + "-- performing full recrawl.  Consider configuring AD server to"
              + " connect directly to FQDN address of domain controller for "
              + "partial updates support.",
              new Object[]{previousServiceName, currentServiceName});
        }
        readEverythingFrom(server, /*includeMembers=*/ false);
        return Collections.emptySet();
      }
      if (!currentInvocationId.equals(previousInvocationId)) {
        log.log(Level.WARNING,
            "Directory Controller {0} has been restored from backup.  "
            + "Performing full recrawl.", currentServiceName);
        readEverythingFrom(server, /*includeMembers=*/ false);
        return Collections.emptySet();
      }
      if (currentHighestUSN == previousHighestUSN) {
        log.log(Level.INFO, "No updates on server {0} -- no crawl invoked.",
            server);
        return Collections.emptySet();
      }
      log.log(Level.INFO, "Attempting incremental crawl.");
      return incrementalCrawl(server, previousHighestUSN, currentHighestUSN);
    }

    private void processEntities(Set<AdEntity> entities, String nETBIOSName) {
      log.log(Level.FINE, "received {0} entities from server", entities.size());
      for (AdEntity e : entities) {
        bySid.put(e.getSid(), e);
        byDn.put(e.getDn(), e);
        // TODO(pjo): Have AdServer put domain into AdEntity during search
        domain.put(e, e.getSid().startsWith("S-1-5-32-") ?
            localizedStrings.get("Builtin") : nETBIOSName);
      }
      initializeMembers(entities);
      resolvePrimaryGroups(entities);
      log.log(Level.FINE, "Ending processing of {0} entities", entities.size());
    }

    @VisibleForTesting
    Set<AdEntity> incrementalCrawl(AdServer server, long previousHighestUSN,
        long currentHighestUSN) throws InterruptedNamingException {
      log.log(Level.FINE, "Starting incremental crawl.");
      // LDAP_MATCHING_RULE_BIT_AND = 1.2.840.113556.1.4.803
      // and ADS_GROUP_TYPE_SECURITY_ENABLED = 2147483648.
      Set<AdEntity> newOrModifiedEntities = server.search("(&(uSNChanged>="
          + (previousHighestUSN + 1) + ")(|(&(objectClass=group)"
          + "(groupType:1.2.840.113556.1.4.803:=2147483648))"
          + "(&(objectClass=user)(objectCategory=person))))",
          /*deleted=*/ false,
          new String[] { "uSNChanged", "sAMAccountName", "objectGUID;binary",
              "objectSid;binary", "userPrincipalName", "primaryGroupId",
              "member", "userAccountControl" });
      // disabled groups handled later, in makeDefs()
      log.log(Level.FINE, "Ending incremental crawl - now starting "
          + "processing.");
      // remove previous value of newly-seen entity, if found
      for (AdEntity e : newOrModifiedEntities) {
        AdEntity oldEntity = bySid.get(e.getSid());
        if (oldEntity != null) {
          entities.remove(oldEntity);
          byDn.remove(oldEntity.getDn());
          members.remove(oldEntity);
          wellKnownMembership.get(everyone).remove(oldEntity.getDn());
        }
      }
      // add the new-or-modified entries to our catalog
      entities.addAll(newOrModifiedEntities);
      processEntities(newOrModifiedEntities, server.getnETBIOSName());
      log.log(Level.FINE, "Ending incremental crawl.");
      return newOrModifiedEntities;
    }

    /**
     * Correctly specify each group's members in the "members" data store
     */
    private void initializeMembers(Set<AdEntity> entities) {
      for (AdEntity entity : entities) {
        if (entity.isGroup()) {
          members.put(entity, new TreeSet<String>(entity.getMembers()));
        }
      }
    }

    /**
     * Make sure that each non-group entity's "primary" group exists in bySid
     *
     * and contains that entity in the <code>members</code> data store.
     */
    private void resolvePrimaryGroups(Set<AdEntity> entities) {
      int nadds = 0;
      int missingGroups = 0;
      for (AdEntity e : entities) {
        if (e.isGroup()) {
          continue;
        }
        AdEntity user = e;
        AdEntity primaryGroup = bySid.get(user.getPrimaryGroupSid());
        if (primaryGroup == null) {
          missingGroups++;
          log.log(Level.WARNING,
              "Group {0} -- primary group for user {1} -- not found",
              new Object[]{user.getPrimaryGroupSid(), user});
          continue;
        }
        if (!members.containsKey(primaryGroup)) {
          members.put(primaryGroup, new TreeSet<String>());
        }
        members.get(primaryGroup).add(user.getDn());
        wellKnownMembership.get(everyone).add(user.getDn());
        nadds++;
      }
      log.log(Level.FINE, "# primary groups: {0}", members.keySet().size());
      if (missingGroups > 0) {
        log.log(Level.FINE, "# missing primary groups: {0}", missingGroups);
      }
      log.log(Level.FINE, "# users added to all primary groups: {0}", nadds);
    }

    void resolveForeignSecurityPrincipals(Set<AdEntity> entities) {
      int nGroups = 0;
      int nNullSid = 0;
      int nNullResolution = 0;
      int nResolved = 0;
      for (AdEntity entity : entities) {
        if (!entity.isGroup() || entity.isWellKnown()) {
          continue;
        }
        nGroups++;
        Set<String> resolvedMembers = new HashSet<String>();
        for (String member : members.get(entity)) {
          String sid = AdEntity.parseForeignSecurityPrincipal(member);
          if (null == sid) {
            resolvedMembers.add(member);
            nNullSid++;
          } else {
            AdEntity resolved = bySid.get(sid);
            if (null == resolved) {
              log.info("unable to resolve foreign principal ["
                  + member + "]; member of [" + entity.getDn());
              nNullResolution++;
            } else {
              resolvedMembers.add(resolved.getDn());
              nResolved++;
            }
          }
        }
        members.put(entity, resolvedMembers);
      }
      log.log(Level.FINE, "#groups: {0}", nGroups);
      log.log(Level.FINE, "#null-SID: {0}", nNullSid);
      log.log(Level.FINE, "#null-resolve: {0}", nNullResolution);
      log.log(Level.FINE, "#resolved: {0}", nResolved);
    }

    Map<GroupPrincipal, List<Principal>> makeDefs(Set<AdEntity> entities) {
      // Merge members with well known group members
      Map<AdEntity, Set<String>> allMembers
          = new HashMap<AdEntity, Set<String>>(members);
      allMembers.putAll(wellKnownMembership);
      Map<GroupPrincipal, List<Principal>> groups
          = new HashMap<GroupPrincipal, List<Principal>>();
      for (AdEntity entity : entities) {
        if (!entity.isGroup()) {
          continue;
        }

        if (!allMembers.containsKey(entity)) {
          continue;
        }

        String groupName = getPrincipalName(entity);
        GroupPrincipal group;
        try {
          group = new GroupPrincipal(groupName, namespace);
        } catch (IllegalArgumentException iae) {
          log.log(Level.WARNING, "Skipping over badly-named group", iae);
          continue;
        }
        List<Principal> def = new ArrayList<Principal>();

        if (!feedBuiltinGroups
            && entity.getSid().startsWith("S-1-5-32-")) {
          log.log(Level.FINER, "Sending empty BUILTIN Group {0}", entity);
          groups.put(group, def);
          continue;
        }

        if (entity.isDisabled()) {
          log.log(Level.FINE, "Skipping {0} members from disabled group {1}",
              new Object[]{entity.getMembers().size(), group});
          groups.put(group, def);
          continue;
        }
        for (String memberDn : allMembers.get(entity)) {
          AdEntity member = byDn.get(memberDn);
          if (member == null) {
            log.info("Unknown member [" + memberDn + "] of group ["
                + entity.getDn());
            continue;
          }
          Principal p;
          String memberName = getPrincipalName(member);
          if (member.isGroup()) {
            try {
              p = new GroupPrincipal(memberName, namespace);
            } catch (IllegalArgumentException iae) {
              String warning = "Skipping badly-named group \"" + memberName
                  + "\" from group \"" + groupName + "\".";
              log.log(Level.WARNING, warning, iae);
              continue;
            }
          } else {
            try {
              p = new UserPrincipal(memberName, namespace);
            } catch (IllegalArgumentException iae) {
              String warning = "Skipping badly-named user \"" + memberName
                  + "\" from group \"" + groupName + "\".";
              log.log(Level.WARNING, warning, iae);
              continue;
            }
          }
          def.add(p);
        }
        if (entity.isWellKnown()) {
          log.log(Level.FINE, "Well known group {0} with # members {1}",
              new Object[]{group, def.size()});
        }
        groups.put(group, def);
      }
      log.log(Level.FINE, "number of groups defined: {0}",
           groups.keySet().size());
      if (log.isLoggable(Level.FINER)) {
        int numGroups = groups.keySet().size();
        int totalMembers = 0;
        for (List<Principal> def : groups.values()) {
          totalMembers += def.size();
        }
        if (0 != numGroups) {
          double mean = ((double) totalMembers) / numGroups;
          log.finer("mean size of defined group: " + mean);
        }
      }
      return groups;
    }

    /*
     * returns principal name for ADEntity object. if domain is available return
     * principal name as samaccountname@domain else just use samaccountname as
     * principal name.
     */
    String getPrincipalName(AdEntity e) {
      return domain.get(e) != null ?
          e.getSAMAccountName() + "@" + domain.get(e) : e.getSAMAccountName();
    }

    /* Combines info of another catalog with this one. */
    void add(GroupCatalog other) {
      entities.addAll(other.entities);
      members.putAll(other.members);
      bySid.putAll(other.bySid);
      byDn.putAll(other.byDn);
      domain.putAll(other.domain);
      for (Object o : wellKnownMembership.keySet()) {
        wellKnownMembership.get(o).addAll(other.wellKnownMembership.get(o));
      }
    }

    void clear() {
      entities.clear();
      members.clear();
      bySid.clear();
      byDn.clear();
      domain.clear();
      wellKnownMembership.clear();
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(
          new Object[] {entities, members, bySid, byDn, domain});
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof GroupCatalog)) {
        return false;
      }
      GroupCatalog gc = (GroupCatalog) o;
      return entities.equals(gc.entities)
          && members.equals(gc.members)
          && bySid.equals(gc.bySid)
          && byDn.equals(gc.byDn)
          && domain.equals(gc.domain)
          && wellKnownMembership.equals(gc.wellKnownMembership);
    }
  }
}
