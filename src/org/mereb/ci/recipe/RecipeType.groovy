package org.mereb.ci.recipe

/**
 * Supported pipeline recipes behind the stable ciV1 entrypoint.
 */
enum RecipeType {
    BUILD('build'),
    PACKAGE('package'),
    IMAGE('image'),
    SERVICE('service'),
    MICROFRONTEND('microfrontend'),
    TERRAFORM('terraform')

    final String value

    RecipeType(String value) {
        this.value = value
    }

    static RecipeType fromValue(Object raw) {
        String normalized = raw?.toString()?.trim()?.toLowerCase()
        if (!normalized) {
            return null
        }
        values().find { RecipeType type -> type.value == normalized }
    }

    @Override
    String toString() {
        return value
    }
}
