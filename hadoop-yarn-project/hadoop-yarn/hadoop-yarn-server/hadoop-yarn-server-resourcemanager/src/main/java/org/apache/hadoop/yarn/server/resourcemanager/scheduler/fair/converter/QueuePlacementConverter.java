/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.converter;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.yarn.server.resourcemanager.placement.DefaultPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.FSPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.PlacementManager;
import org.apache.hadoop.yarn.server.resourcemanager.placement.PlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.PrimaryGroupPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.RejectPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.SecondaryGroupExistingPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.SpecifiedPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.placement.UserPlacementRule;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.placement.schema.MappingRulesDescription;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.placement.schema.Rule;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.placement.schema.Rule.FallbackResult;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.placement.schema.Rule.Policy;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.placement.schema.Rule.Type;

class QueuePlacementConverter {
  private static final FallbackResult SKIP_RESULT = FallbackResult.SKIP;
  private static final String DEFAULT_QUEUE = "root.default";
  private static final String MATCH_ALL_USER = "*";

  MappingRulesDescription convertPlacementPolicy(
      PlacementManager placementManager,
      FSConfigToCSConfigRuleHandler ruleHandler,
      CapacitySchedulerConfiguration convertedCSconfig) {

    MappingRulesDescription desc = new MappingRulesDescription();
    List<Rule> rules = new ArrayList<>();

    for (final PlacementRule fsRule : placementManager.getPlacementRules()) {
      boolean create = ((FSPlacementRule)fsRule).getCreateFlag();

      if (fsRule instanceof UserPlacementRule) {
        UserPlacementRule userRule = (UserPlacementRule) fsRule;

        // nested rule
        if (userRule.getParentRule() != null) {
          handleNestedRule(rules,
              userRule,
              ruleHandler,
              create,
              convertedCSconfig);
        } else {
          rules.add(createRule(Policy.USER, create, ruleHandler));
        }
      } else if (fsRule instanceof SpecifiedPlacementRule) {
        rules.add(createRule(Policy.SPECIFIED, create, ruleHandler));
      } else if (fsRule instanceof PrimaryGroupPlacementRule) {
        rules.add(createRule(Policy.PRIMARY_GROUP, create, ruleHandler));
      } else if (fsRule instanceof DefaultPlacementRule) {
        DefaultPlacementRule defaultRule = (DefaultPlacementRule) fsRule;
        String defaultQueueName = defaultRule.defaultQueueName;

        Rule rule;
        if (DEFAULT_QUEUE.equals(defaultQueueName)) {
          rule = createRule(Policy.DEFAULT_QUEUE, create, ruleHandler);
        } else {
          rule = createRule(Policy.CUSTOM, create, ruleHandler);
          rule.setCustomPlacement(defaultQueueName);
        }

        rules.add(rule);
      } else if (fsRule instanceof SecondaryGroupExistingPlacementRule) {
        Rule rule = createRule(Policy.SECONDARY_GROUP, create, ruleHandler);
        rules.add(rule);
      } else if (fsRule instanceof RejectPlacementRule) {
        rules.add(createRule(Policy.REJECT, false, ruleHandler));
      } else {
        throw new IllegalArgumentException("Unknown placement rule: " + fsRule);
      }
    }

    desc.setRules(rules);

    return desc;
  }

  private void handleNestedRule(List<Rule> rules,
      UserPlacementRule userRule,
      FSConfigToCSConfigRuleHandler ruleHandler,
      boolean create,
      CapacitySchedulerConfiguration csConf) {
    PlacementRule parentRule = userRule.getParentRule();
    boolean parentCreate = ((FSPlacementRule) parentRule).getCreateFlag();
    Policy policy;
    String queueName = null;

    if (parentRule instanceof PrimaryGroupPlacementRule) {
      policy = Policy.PRIMARY_GROUP_USER;
    } else if (parentRule instanceof SecondaryGroupExistingPlacementRule) {
      policy = Policy.SECONDARY_GROUP_USER;
    } else if (parentRule instanceof DefaultPlacementRule) {
      DefaultPlacementRule defaultRule = (DefaultPlacementRule) parentRule;
      policy = Policy.USER;
      queueName = defaultRule.defaultQueueName;
    } else {
      throw new IllegalArgumentException(
          "Unsupported parent nested rule: "
          + parentRule.getClass().getCanonicalName());
    }

    Rule rule = createNestedRule(policy,
        create,
        ruleHandler,
        parentCreate,
        queueName,
        csConf);
    rules.add(rule);
  }

  private Rule createRule(Policy policy, boolean create,
      FSConfigToCSConfigRuleHandler ruleHandler) {
    Rule rule = new Rule();
    rule.setPolicy(policy);
    rule.setCreate(create);
    rule.setMatches(MATCH_ALL_USER);
    rule.setFallbackResult(SKIP_RESULT);
    rule.setType(Type.USER);

    if (create) {
      // display warning that these queues must exist and
      // cannot be created automatically under "root"
      if (policy == Policy.PRIMARY_GROUP
          || policy == Policy.PRIMARY_GROUP_USER) {
        ruleHandler.handleRuleAutoCreateFlag("root.<primaryGroup>");
      } else if (policy == Policy.SECONDARY_GROUP
          || policy == Policy.SECONDARY_GROUP_USER) {
        // in theory, root.<secondaryGroup> must always exist, even in FS,
        // but display the warning anyway
        ruleHandler.handleRuleAutoCreateFlag("root.<secondaryGroup>");
      }
    }

    return rule;
  }

  private Rule createNestedRule(Policy policy,
      boolean create,
      FSConfigToCSConfigRuleHandler ruleHandler,
      boolean fsParentCreate,
      String parentQueue,
      CapacitySchedulerConfiguration csConf) {

    Rule rule = createRule(policy, create, ruleHandler);

    if (parentQueue != null) {
      rule.setParentQueue(parentQueue);
    }

    // create flag for the parent rule is not supported
    if (fsParentCreate) {
      if (policy == Policy.PRIMARY_GROUP_USER) {
        ruleHandler.handleFSParentCreateFlag("root.<primaryGroup>");
      } else if (policy == Policy.SECONDARY_GROUP_USER) {
        ruleHandler.handleFSParentCreateFlag("root.<secondaryGroup>");
      } else {
        ruleHandler.handleFSParentCreateFlag(parentQueue);
      }
    }

    // check if parent conflicts with existing static queues
    if (create && policy == Policy.USER) {
      ruleHandler.handleRuleAutoCreateFlag(parentQueue);
      checkStaticDynamicConflict(parentQueue, csConf, ruleHandler);
    }

    return rule;
  }

  private void checkStaticDynamicConflict(String parentPath,
      CapacitySchedulerConfiguration csConf,
      FSConfigToCSConfigRuleHandler ruleHandler) {
    String[] childQueues = csConf.getQueues(parentPath);

    // User must be warned: static + dynamic queues are under the
    // same parent
    if (childQueues != null && childQueues.length > 0) {
      ruleHandler.handleChildStaticDynamicConflict(parentPath);
    }
  }
}
