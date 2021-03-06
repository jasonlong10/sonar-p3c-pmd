/*
 * SonarQube PMD Plugin
 * Copyright (C) 2012 ${owner}
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.pmd;

import com.google.common.annotations.VisibleForTesting;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import org.apache.commons.lang3.StringUtils;
import org.jdom.CDATA;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.sonar.api.profiles.ProfileExporter;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.rules.ActiveRule;
import org.sonar.api.rules.ActiveRuleParam;
import org.sonar.plugins.java.Java;
import org.sonar.plugins.pmd.xml.PmdProperty;
import org.sonar.plugins.pmd.xml.PmdRule;
import org.sonar.plugins.pmd.xml.PmdRuleset;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public class PmdProfileExporter extends ProfileExporter {
  public PmdProfileExporter() {
    super(PmdConstants.REPOSITORY_KEY, PmdConstants.PLUGIN_NAME);
    setSupportedLanguages(Java.KEY);
    setMimeType("application/xml");
  }

  @Override
  public void exportProfile(RulesProfile profile, Writer writer) {
    String profileName = profile.getName();
    PmdRuleset tree = createPmdRuleset(PmdConstants.REPOSITORY_KEY, profile.getActiveRulesByRepository(PmdConstants.REPOSITORY_KEY), profileName);
    exportPmdRulesetToXml(tree, writer, profileName);
  }

  public String exportProfile(String repositoryKey, RulesProfile profile) {
    String profileName = profile.getName();
    PmdRuleset tree = createPmdRuleset(repositoryKey, profile.getActiveRulesByRepository(repositoryKey), profileName);
    StringWriter stringWriter = new StringWriter();
    exportPmdRulesetToXml(tree, stringWriter, profileName);
    return stringWriter.toString();
  }

  private PmdRuleset createPmdRuleset(String repositoryKey, List<ActiveRule> activeRules, String profileName) {
    PmdRuleset ruleset = new PmdRuleset(profileName);
    for (ActiveRule activeRule : activeRules) {
      if (activeRule.getRule().getRepositoryKey().equals(repositoryKey)) {
        String configKey = activeRule.getRule().getConfigKey();
        PmdRule rule = new PmdRule(configKey, PmdLevelUtils.toLevel(activeRule.getSeverity()));
        addRuleProperties(activeRule, rule);
        ruleset.addRule(rule);
        processXPathRule(activeRule.getRuleKey(), rule);
      }
    }
    return ruleset;
  }

  private static void addRuleProperties(ActiveRule activeRule, PmdRule pmdRule) {
    if ((activeRule.getActiveRuleParams() != null) && !activeRule.getActiveRuleParams().isEmpty()) {
      List<PmdProperty> properties = new ArrayList<>();
      for (ActiveRuleParam activeRuleParam : activeRule.getActiveRuleParams()) {
        properties.add(new PmdProperty(activeRuleParam.getRuleParam().getKey(), activeRuleParam.getValue()));
      }
      pmdRule.setProperties(properties);
    }
  }

  @VisibleForTesting
  static void processXPathRule(String sonarRuleKey, PmdRule rule) {
    if (PmdConstants.XPATH_CLASS.equals(rule.getRef())) {
      rule.setRef(null);
      PmdProperty xpathMessage = rule.getProperty(PmdConstants.XPATH_MESSAGE_PARAM);
      if (xpathMessage == null) {
        throw new IllegalArgumentException("Property '" + PmdConstants.XPATH_MESSAGE_PARAM + "' should be set for PMD rule " + sonarRuleKey);
      }
      rule.setMessage(xpathMessage.getValue());
      rule.removeProperty(PmdConstants.XPATH_MESSAGE_PARAM);
      PmdProperty xpathExp = rule.getProperty(PmdConstants.XPATH_EXPRESSION_PARAM);
      if (xpathExp == null) {
        throw new IllegalArgumentException("Property '" + PmdConstants.XPATH_EXPRESSION_PARAM + "' should be set for PMD rule " + sonarRuleKey);
      }
      xpathExp.setCdataValue(xpathExp.getValue());
      rule.setClazz(PmdConstants.XPATH_CLASS);
      rule.setLanguage(JavaLanguageModule.TERSE_NAME);
      rule.setName(sonarRuleKey);
    }
  }

  private static void exportPmdRulesetToXml(PmdRuleset pmdRuleset, Writer writer, String profileName) {
    Element eltRuleset = new Element("ruleset");
    for (PmdRule pmdRule : pmdRuleset.getPmdRules()) {
      Element eltRule = new Element("rule");
      addAttribute(eltRule, "ref", pmdRule.getRef());
      addAttribute(eltRule, "class", pmdRule.getClazz());
      addAttribute(eltRule, "message", pmdRule.getMessage());
      addAttribute(eltRule, "name", pmdRule.getName());
      addAttribute(eltRule, "language", pmdRule.getLanguage());
      addChild(eltRule, "priority", pmdRule.getPriority());
      if (pmdRule.hasProperties()) {
        Element ruleProperties = processRuleProperties(pmdRule);
        if (ruleProperties.getContentSize() > 0) {
          eltRule.addContent(ruleProperties);
        }
      }
      eltRuleset.addContent(eltRule);
    }
    XMLOutputter serializer = new XMLOutputter(Format.getPrettyFormat());
    try {
      serializer.output(new Document(eltRuleset), writer);
    } catch (IOException e) {
      throw new IllegalStateException("An exception occurred while generating the PMD configuration file from profile: " + profileName, e);
    }
  }

  private static Element processRuleProperties(PmdRule pmdRule) {
    Element eltProperties = new Element("properties");
    for (PmdProperty prop : pmdRule.getProperties()) {
      if (isPropertyValueNotEmpty(prop)) {
        Element eltProperty = new Element("property");
        eltProperty.setAttribute("name", prop.getName());
        if (prop.isCdataValue()) {
          Element eltValue = new Element("value");
          eltValue.addContent(new CDATA(prop.getCdataValue()));
          eltProperty.addContent(eltValue);
        } else {
          eltProperty.setAttribute("value", prop.getValue());
        }
        eltProperties.addContent(eltProperty);
      }
    }
    return eltProperties;
  }

  private static boolean isPropertyValueNotEmpty(PmdProperty prop) {
    if (prop.isCdataValue()) {
      return StringUtils.isNotEmpty(prop.getCdataValue());
    }
    return StringUtils.isNotEmpty(prop.getValue());
  }

  private static void addChild(Element elt, String name, @Nullable String text) {
    if (text != null) {
      elt.addContent(new Element(name).setText(text));
    }
  }

  private static void addAttribute(Element elt, String name, @Nullable String value) {
    if (value != null) {
      elt.setAttribute(name, value);
    }
  }
}
