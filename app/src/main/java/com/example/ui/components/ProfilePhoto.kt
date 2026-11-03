package com.example.ui.components

import java.io.File

/**
 * O que entregar ao carregador de imagem para a foto do perfil.
 * Fotos novas ficam numa cópia privada do app, e o perfil guarda o CAMINHO dessa cópia (começa com "/");
 * fotos antigas guardam o endereço temporário da galeria (content://). Caminho vira [File]; o resto segue como estava.
 */
fun profilePhotoModel(photoUri: String): Any =
    if (photoUri.startsWith("/")) File(photoUri) else photoUri
