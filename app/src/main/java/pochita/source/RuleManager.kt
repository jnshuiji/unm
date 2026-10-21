package pochita.source

/**
 * Backward compatibility typealias redirecting to the refactored [pochita.rule.RuleManager].
 */
@Deprecated(
    message = "RuleManager has moved to pochita.rule.RuleManager",
    replaceWith = ReplaceWith("RuleManager", "pochita.rule.RuleManager")
)
typealias RuleManager = pochita.rule.RuleManager
