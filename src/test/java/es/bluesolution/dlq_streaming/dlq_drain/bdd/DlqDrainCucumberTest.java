package es.bluesolution.dlq_streaming.dlq_drain.bdd;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectPackages("features.dlq_drain")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "es.bluesolution.dlq_streaming.dlq_drain.bdd")
@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME, value = "pretty")
class DlqDrainCucumberTest {
}



