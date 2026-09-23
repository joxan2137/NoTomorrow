import Foundation

/// Open Food Facts responses captured live on 2026-09-22 (diag/barcode.md §3.3, scratchpad work/barcode/fixtures),
/// trimmed to the fields the app requests. The Android mapping tests use the same JSON.
enum OFFFixtures {
    /// Kopernik "Bagatelka": `product_name` / `product_name_pl` empty, only `product_name_en`; full nutrition.
    static let nameOnlyEnglish = Data(#"""
    {
     "code": "5900056007181",
     "status": 1,
     "product": {
      "code": "5900056007181",
      "product_name": "",
      "product_name_pl": "",
      "brands": "Kopernik Toruń",
      "quantity": "135g",
      "serving_size": "100 g",
      "serving_quantity": 100,
      "nutriments": {
       "carbohydrates_100g": 73,
       "energy-kcal_100g": 399,
       "energy-kj_100g": 1686,
       "energy_100g": 1686,
       "fat_100g": 8.4,
       "proteins_100g": 6.6
      },
      "image_front_small_url": "https://images.openfoodfacts.org/images/products/590/005/600/7181/front_pl.25.200.jpg",
      "countries_tags": [
       "en:poland"
      ],
      "product_name_en": "Bagatelka",
      "generic_name": "",
      "generic_name_pl": "",
      "lang": "pl"
     }
    }
    """#.utf8)

    /// Pudliszki concentrate: no name in any field, brand + quantity only; full nutrition.
    static let noNameBrandQuantity = Data(#"""
    {
     "code": "5900783000134",
     "status": 1,
     "product": {
      "code": "5900783000134",
      "product_name": "",
      "product_name_pl": "",
      "brands": "Pudliszki",
      "quantity": "200 g",
      "serving_size": "200g",
      "serving_quantity": 200,
      "nutriments": {
       "carbohydrates_100g": 18,
       "energy-kcal_100g": 104,
       "energy-kj_100g": 439,
       "energy_100g": 439,
       "fat_100g": 0.8,
       "fiber_100g": 2.5,
       "proteins_100g": 4.9
      },
      "image_front_small_url": "https://images.openfoodfacts.org/images/products/590/078/300/0134/front_pl.4.200.jpg",
      "countries_tags": [
       "en:poland"
      ],
      "product_name_en": "",
      "generic_name": "",
      "generic_name_pl": "",
      "lang": "pl",
      "nutriments_estimated": {}
     }
    }
    """#.utf8)

    /// Lidl mini kiwi: empty `nutriments`, OFF's own `nutriments_estimated`; main language French.
    static let estimatedOnly = Data(#"""
    {
     "code": "20582555",
     "status": 1,
     "product": {
      "code": "20582555",
      "product_name": "Mini kiwi",
      "brands": "Lidl, Nergi",
      "quantity": "125 g",
      "nutriments": {},
      "image_front_small_url": "https://images.openfoodfacts.org/images/products/000/002/058/2555/front_pl.18.200.jpg",
      "countries_tags": [
       "en:croatia",
       "en:france"
      ],
      "generic_name": "",
      "lang": "fr",
      "nutriments_estimated": {
       "carbohydrates_100g": 11,
       "energy-kcal_100g": 60.5,
       "energy-kj_100g": 255,
       "energy_100g": 255,
       "fat_100g": 0.6,
       "fiber_100g": 2.4,
       "proteins_100g": 0.88
      }
     }
    }
    """#.utf8)

    /// Carrefour "Łosoś świeży" MOWI: name, brand, 150 g, no nutrition at all.
    static let nameNoNutrition = Data(#"""
    {
     "code": "2050401935713",
     "status": 1,
     "product": {
      "code": "2050401935713",
      "product_name": "Łosoś świeży",
      "product_name_pl": "Łosoś świeży",
      "brands": "MOWI",
      "quantity": "150 g",
      "serving_size": "150 g",
      "serving_quantity": 150,
      "image_front_small_url": "https://images.openfoodfacts.org/images/products/205/040/193/5713/front_pl.16.200.jpg",
      "countries_tags": [
       "en:poland"
      ],
      "lang": "pl"
     }
    }
    """#.utf8)

    /// Żabka code: a stub with every field empty.
    static let emptyStub = Data(#"""
    {
     "code": "5901067400831",
     "status": 1,
     "product": {
      "code": "5901067400831",
      "product_name": "",
      "product_name_pl": "",
      "brands": "",
      "quantity": "",
      "countries_tags": [
       "en:poland"
      ],
      "generic_name": "",
      "generic_name_pl": "",
      "lang": "de"
     }
    }
    """#.utf8)

    /// The v2 body OFF sends with HTTP 404.
    static let notFound = Data(#"""
    {
     "code": "6903082830807",
     "status": 0,
     "status_verbose": "product not found"
    }
    """#.utf8)

    /// search-a-licious `/search?q=serek wiejski&langs=pl,en`: `hits`, `brands` as an array, one hit named only in
    /// English, one without nutriments (dropped).
    static let searchALicious = Data(#"""
    {
     "hits": [
      {
       "code": "0444444143006",
       "brands": [
        "Piątnica"
       ],
       "countries_tags": [
        "en:poland"
       ],
       "nutriments": {
        "sugars_100g": 1.5,
        "proteins_100g": 11,
        "fat_100g": 5,
        "salt_100g": 0.7,
        "saturated-fat_100g": 3.5,
        "carbohydrates_100g": 2,
        "sodium_100g": 0.28,
        "energy-kcal_100g": 97
       },
       "product_name": "Serek wiejski",
       "product_name_en": "Serek wiejski"
      },
      {
       "code": "5900531000935",
       "brands": [
        "Piątnica"
       ],
       "countries_tags": [
        "en:poland"
       ],
       "nutriments": {
        "energy-kcal_100g": 110,
        "carbohydrates_100g": 10,
        "fat_100g": 4,
        "proteins_100g": 8.4
       },
       "product_name": "Serek wiejski",
       "product_name_en": "Serek wiejski"
      },
      {
       "code": "5900531050015",
       "brands": [
        "Piątnica"
       ],
       "quantity": "200 g",
       "countries_tags": [
        "en:poland"
       ],
       "nutriments": {
        "carbohydrates_100g": 2,
        "energy-kcal_100g": 97,
        "energy-kj_100g": 406,
        "fat_100g": 5,
        "proteins_100g": 11,
        "salt_100g": 0.7,
        "saturated-fat_100g": 3.5,
        "sodium_100g": 0.28,
        "sugars_100g": 1.5
       },
       "product_name": "Serek wiejski",
       "product_name_en": "Serek wiejski",
       "image_front_small_url": "https://images.openfoodfacts.org/images/products/590/053/105/0015/front_it.6.200.jpg"
      },
      {
       "code": "5900512987378",
       "quantity": "200g",
       "countries_tags": [
        "en:poland"
       ],
       "nutriments": {
        "carbohydrates_100g": 2,
        "energy-kcal_100g": 97,
        "energy-kj_100g": 406,
        "fat_100g": 5,
        "fiber_100g": 0,
        "proteins_100g": 11,
        "salt_100g": 0.7,
        "saturated-fat_100g": 3.3,
        "sodium_100g": 0.28,
        "sugars_100g": 2
       },
       "product_name_en": "Serek wiejski bez laktozy",
       "image_front_small_url": "https://images.openfoodfacts.org/images/products/590/051/298/7378/front_pl.18.200.jpg"
      },
      {
       "code": "5900512980966",
       "brands": [
        "Mlekovita"
       ],
       "countries_tags": [
        "en:poland"
       ],
       "product_name": "Mlekovita Serek Wiejski Bez Laktozy",
       "product_name_en": "Mlekovita Serek Wiejski Bez Laktozy",
       "image_front_small_url": "https://images.openfoodfacts.org/images/products/590/051/298/0966/front_en.3.200.jpg"
      }
     ],
     "page": 1,
     "page_size": 24,
     "count": 112
    }
    """#.utf8)
}
