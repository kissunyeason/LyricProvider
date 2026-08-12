/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.saltprovider.xposed

import io.github.proify.lyricon.provider.ProviderLogo

object SaltPlayer : MeizuProvider(
    Constants.SALT_PLAYER_PACKAGE_NAME,
    logo = ProviderLogo.fromBase64(Constants.ICON)
)