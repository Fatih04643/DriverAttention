package com.example.driverattention.analysis

/**
 * MediaPipe Face Mesh (478 nokta) içinde kullandığımız noktaların indeksleri.
 *
 * Göz noktaları EAR formülündeki sıraya göre verilmiştir:
 *   p1 = dış köşe, p4 = iç köşe (yatay eksen)
 *   p2, p3 = üst kapak; p6, p5 = alt kapak (p2↔p6 ve p3↔p5 dikey çiftler)
 */
object FaceLandmarkIndices {
    // [p1, p2, p3, p4, p5, p6]
    val LEFT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
    val RIGHT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)

    // Ağız: iç dudak üst/alt (dikey) ve ağız köşeleri (yatay)
    const val MOUTH_UPPER_INNER = 13
    const val MOUTH_LOWER_INNER = 14
    const val MOUTH_LEFT_CORNER = 78
    const val MOUTH_RIGHT_CORNER = 308

    /** Overlay'de vurgulanacak tüm noktalar */
    val HIGHLIGHTED: Set<Int> = buildSet {
        LEFT_EYE.forEach { add(it) }
        RIGHT_EYE.forEach { add(it) }
        add(MOUTH_UPPER_INNER); add(MOUTH_LOWER_INNER)
        add(MOUTH_LEFT_CORNER); add(MOUTH_RIGHT_CORNER)
    }
}
