package com.quarkdown.cli.creator.content

import com.quarkdown.core.pipeline.output.OutputResource

/**
 * A [ProjectCreatorInitialContentSupplier] that provides no initial content or resources.
 */
class EmptyProjectCreatorInitialContentSupplier : ProjectCreatorInitialContentSupplier {
    override val templateCodeContentResource: String? = null

    override fun createResources(): Set<OutputResource> = emptySet()
}
