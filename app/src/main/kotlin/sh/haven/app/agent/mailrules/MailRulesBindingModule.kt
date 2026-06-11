package sh.haven.app.agent.mailrules

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.data.mailrule.MailRulePendingRunner
import sh.haven.core.data.mailrule.MailWatchPoker

/**
 * Binds the app-layer Mail-Rules implementations to the `core/data` interfaces the
 * feature-layer rules UI depends on. The impls ([MailRuleActionExecutor],
 * [MailWatchManager]) are `@Singleton`s already in the graph; this just exposes the
 * approval + poke surfaces across the app→feature boundary.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MailRulesBindingModule {
    @Binds
    abstract fun bindPendingRunner(impl: MailRuleActionExecutor): MailRulePendingRunner

    @Binds
    abstract fun bindWatchPoker(impl: MailWatchManager): MailWatchPoker
}
