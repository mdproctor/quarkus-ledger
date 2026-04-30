package io.casehub.ledger.runtime.privacy;

/** Pass-through implementation — stores decision context JSON unchanged. */
public class PassThroughDecisionContextSanitiser implements DecisionContextSanitiser {

    @Override
    public String sanitise(final String decisionContextJson) {
        return decisionContextJson;
    }
}
