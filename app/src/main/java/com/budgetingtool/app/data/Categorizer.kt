package com.budgetingtool.app.data

object DefaultCategories {
    const val GROCERIES = "cat_groceries"
    const val DINING = "cat_dining"
    const val GAS = "cat_gas"
    const val SHOPPING = "cat_shopping"
    const val UTILITIES = "cat_utilities"
    const val HOUSING = "cat_housing"
    const val TRANSPORT = "cat_transport"
    const val HEALTH = "cat_health"
    const val ENTERTAINMENT = "cat_entertainment"
    const val SUBSCRIPTIONS = "cat_subscriptions"
    const val TRAVEL = "cat_travel"
    const val KIDS = "cat_kids"
    const val PERSONAL = "cat_personal"
    const val INSURANCE = "cat_insurance"
    const val GIFTS = "cat_gifts"
    const val FEES = "cat_fees"
    const val OTHER = "cat_other"
    const val INCOME = "cat_income"
    const val TRANSFER = "cat_transfer"

    /** Seeded with updatedAt = 1 and fixed ids, so both phones start identical and any edit wins. */
    val all: List<Category> = listOf(
        Category(GROCERIES, "Groceries", "🛒", 0xFF43A047, updatedAt = 1),
        Category(DINING, "Dining & coffee", "🍽️", 0xFFFB8C00, updatedAt = 1),
        Category(GAS, "Gas", "⛽", 0xFF6D4C41, updatedAt = 1),
        Category(SHOPPING, "Shopping", "🛍️", 0xFF8E24AA, updatedAt = 1),
        Category(UTILITIES, "Utilities & phone", "💡", 0xFFFDD835, updatedAt = 1),
        Category(HOUSING, "Housing", "🏠", 0xFF3949AB, updatedAt = 1),
        Category(TRANSPORT, "Car & transport", "🚗", 0xFF546E7A, updatedAt = 1),
        Category(HEALTH, "Health & pharmacy", "⚕️", 0xFFE53935, updatedAt = 1),
        Category(ENTERTAINMENT, "Entertainment", "🎬", 0xFFD81B60, updatedAt = 1),
        Category(SUBSCRIPTIONS, "Subscriptions", "📺", 0xFF00ACC1, updatedAt = 1),
        Category(TRAVEL, "Travel", "✈️", 0xFF1E88E5, updatedAt = 1),
        Category(KIDS, "Kids & family", "👪", 0xFFFF7043, updatedAt = 1),
        Category(PERSONAL, "Personal care", "💇", 0xFFAB47BC, updatedAt = 1),
        Category(INSURANCE, "Insurance", "🛡️", 0xFF5C6BC0, updatedAt = 1),
        Category(GIFTS, "Gifts & donations", "🎁", 0xFFEC407A, updatedAt = 1),
        Category(FEES, "Fees & interest", "⚠️", 0xFF757575, updatedAt = 1),
        Category(OTHER, "Other", "❓", 0xFF9E9E9E, updatedAt = 1),
        Category(INCOME, "Income", "💰", 0xFF2E7D32, CategoryKind.INCOME, updatedAt = 1),
        Category(TRANSFER, "Transfers & card payments", "🔁", 0xFF78909C, CategoryKind.TRANSFER, updatedAt = 1),
    )
}

/**
 * Turns raw statement descriptions into merchant names and categories.
 * User rules always win over the built-in keyword list.
 */
object Categorizer {
    private val prefixes = listOf(
        "PURCHASE AUTHORIZED ON", "DEBIT CARD PURCHASE", "POS PURCHASE", "POS DEBIT", "DBT CRD", "DEBIT PURCHASE",
        "VISA DDA PUR", "CHECKCARD", "CHECK CARD", "RECURRING PAYMENT", "RECURRING", "PURCHASE", "ACH DEBIT",
        "ACH CREDIT", "ACH", "POS", "DEBIT", "SQ *", "SQ*", "TST*", "TST *", "PP*", "PAYPAL *", "SP *", "SP*", "THE ",
    )

    /**
     * Order matters: "UBER EATS" before "UBER", "ENTERPRISE RENT" (travel) before "RENT" (housing),
     * "AMAZON PRIME" before "AMAZON". Transfers come last so a named biller wins over generic
     * wording: "ONLINE PAYMENT MORTGAGE" is housing, "T-MOBILE AUTOPAY" is a phone bill.
     */
    private val keywordTable: List<Pair<String, List<String>>> = listOf(
        DefaultCategories.INCOME to listOf(
            "PAYROLL", "DIRECT DEP", "DIR DEP", "DIRECTDEP", "SALARY", "DIVIDEND", "INTEREST PAID", "INTEREST EARNED",
            "TAX REFUND", "IRS TREAS", "NYS DTF", "SSA TREAS", "CASHBACK", "CASH BACK REWARD", "STATEMENT CREDIT",
        ),
        DefaultCategories.FEES to listOf(
            "INTEREST CHARGE", "LATE FEE", "ANNUAL FEE", "OVERDRAFT", "SERVICE CHARGE", "MAINTENANCE FEE", "ATM FEE",
            "FOREIGN TRANSACTION", "NSF FEE", "RETURNED ITEM",
        ),
        DefaultCategories.SUBSCRIPTIONS to listOf(
            "NETFLIX", "HULU", "SPOTIFY", "DISNEY PLUS", "DISNEYPLUS", "DISNEY+", "APPLE.COM/BILL", "APPLE COM BILL",
            "GOOGLE STORAGE", "GOOGLE ONE", "YOUTUBE", "PARAMOUNT", "PEACOCK", "MAX.COM", "HBO", "AUDIBLE",
            "AMAZON PRIME", "PRIME VIDEO", "AMZN DIGITAL", "PATREON", "ADOBE", "MICROSOFT", "ICLOUD", "SIRIUSXM",
            "PLANET FITNESS", "CRUNCH FITNESS", "PELOTON", "CHATGPT", "OPENAI", "ANTHROPIC", "DROPBOX",
        ),
        DefaultCategories.DINING to listOf(
            "UBER EATS", "UBEREATS", "DOORDASH", "GRUBHUB", "MCDONALD", "STARBUCKS", "DUNKIN", "TIM HORTONS",
            "CHIPOTLE", "PIZZA", "RESTAURANT", "CAFE", "COFFEE", "SUBWAY", "WENDY", "BURGER", "TACO BELL",
            "CHICK-FIL-A", "CHICK FIL A", "PANERA", "DOMINO", "GRILL", "DINER", "TAVERN", "BREWING", "BREWERY",
            "APPLEBEE", "OLIVE GARDEN", "BUFFALO WILD", "ARBY", "KFC", "POPEYES", "FIVE GUYS", "SONIC DRIVE",
            "DAIRY QUEEN", "PANDA EXPRESS", "JERSEY MIKE", "JIMMY JOHN", "TEXAS ROADHOUSE", "RED LOBSTER",
            "BAKERY", "BAGEL", "WINGS", "SUSHI", "THAI", "TACO",
        ),
        DefaultCategories.GROCERIES to listOf(
            "WEGMANS", "TOPS MARKET", "TOPS FRIENDLY", "TOPS #", "ALDI", "PRICE CHOPPER", "MARKET 32", "HANNAFORD",
            "KROGER", "SAFEWAY", "PUBLIX", "WHOLE FOODS", "WHOLEFDS", "TRADER JOE", "SAVE A LOT", "SAVE-A-LOT",
            "LIDL", "GROCERY", "SUPERMARKET", "SHOPRITE", "FOOD LION", "GIANT EAGLE", "STOP & SHOP", "COSTCO",
            "SAMS CLUB", "SAM'S CLUB", "BJ'S WHOLESALE", "BJS WHOLESALE", "INSTACART", "FRESH MARKET", "PRICE RITE",
        ),
        DefaultCategories.GAS to listOf(
            "SHELL", "EXXON", "MOBIL", "SUNOCO", "STEWART'S", "STEWARTS SHOP", "SPEEDWAY", "CITGO", "BP#", "BP ",
            "FASTRAC", "KWIK FILL", "CUMBERLAND", "MARATHON", "VALERO", "WAWA", "SHEETZ", "CIRCLE K", "GULF OIL",
            "GETTY", "SUNOCO", "FUEL", "GAS STATION", "EXPRESSMART", "QUICKWAY",
        ),
        DefaultCategories.UTILITIES to listOf(
            "NATIONAL GRID", "NATL GRID", "NYSEG", "RG&E", "RGE ", "NATIONAL FUEL", "CON ED", "CONED", "VERIZON",
            "AT&T", "T-MOBILE", "TMOBILE", "SPECTRUM", "CHARTER COMM", "COMCAST", "XFINITY", "WATER AUTH",
            "WATER DEPT", "ELECTRIC", "MINT MOBILE", "VISIBLE", "CRICKET", "STARLINK", "GREENLIGHT",
        ),
        DefaultCategories.TRANSPORT to listOf(
            "UBER", "LYFT", "PARKING", "TOLL", "E-ZPASS", "EZPASS", "THRUWAY", "DMV", "JIFFY LUBE", "VALVOLINE",
            "AUTOZONE", "ADVANCE AUTO", "NAPA", "CAR WASH", "FIRESTONE", "MONRO", "MIDAS", "TESLA SUPERCHARGER",
            "AMTRAK", "GREYHOUND", "TIRE",
        ),
        DefaultCategories.HEALTH to listOf(
            "CVS", "WALGREENS", "RITE AID", "PHARMACY", "MEDICAL", "DENTAL", "DENTIST", "HOSPITAL", "DOCTOR",
            "OPTOM", "URGENT CARE", "PEDIATRIC", "CLINIC", "LABCORP", "QUEST DIAG", "VISION", "THERAPY",
        ),
        DefaultCategories.ENTERTAINMENT to listOf(
            "AMC ", "REGAL", "CINEMA", "THEATRE", "THEATER", "STEAM", "PLAYSTATION", "XBOX", "NINTENDO",
            "TICKETMASTER", "STUBHUB", "BOWL", "GOLF", "MUSEUM", "ZOO", "CONCERT", "FANDANGO",
        ),
        DefaultCategories.TRAVEL to listOf(
            "AIRLINE", "AIRWAYS", "DELTA AIR", "JETBLUE", "SOUTHWEST", "UNITED AIR", "AMERICAN AIR", "SPIRIT AIR",
            "FRONTIER", "HOTEL", "MARRIOTT", "HILTON", "HYATT", "HOLIDAY INN", "AIRBNB", "VRBO", "EXPEDIA",
            "BOOKING.COM", "HERTZ", "ENTERPRISE RENT", "AVIS", "NATIONAL CAR",
        ),
        DefaultCategories.HOUSING to listOf(
            "RENT", "MORTGAGE", "HOA ", "PROPERTY MGMT", "APARTMENTS", "MR COOPER", "ROCKET MORTGAGE", "HOME DEPOT",
            "LOWE'S", "LOWES", "MENARDS", "ACE HARDWARE", "TRUE VALUE",
        ),
        DefaultCategories.INSURANCE to listOf(
            "GEICO", "PROGRESSIVE", "STATE FARM", "ALLSTATE", "ERIE INS", "LIBERTY MUTUAL", "NATIONWIDE",
            "INSURANCE", "USAA", "TRAVELERS",
        ),
        DefaultCategories.KIDS to listOf(
            "DAYCARE", "CHILDCARE", "CHILD CARE", "PRESCHOOL", "SCHOOL", "CARTER'S", "OSHKOSH", "BABIES",
            "BUY BUY BABY", "TOYS", "CHILDRENS PLACE",
        ),
        DefaultCategories.PERSONAL to listOf(
            "SALON", "BARBER", "SPA ", "ULTA", "SEPHORA", "GREAT CLIPS", "SUPERCUTS", "NAILS", "MASSAGE",
        ),
        DefaultCategories.GIFTS to listOf(
            "DONATION", "CHARITY", "CHURCH", "GOFUNDME", "RED CROSS", "UNITED WAY", "PARISH", "MINISTRIES",
        ),
        DefaultCategories.SHOPPING to listOf(
            "AMAZON", "AMZN", "TARGET", "WALMART", "WAL-MART", "WM SUPERCENTER", "KOHL", "KOHLS", "KOHL'S", "BEST BUY", "TJ MAXX",
            "TJMAXX", "MARSHALLS", "HOMEGOODS", "ETSY", "EBAY", "DOLLAR GENERAL", "DOLLAR TREE", "FIVE BELOW",
            "IKEA", "OLD NAVY", "GAP ", "MACY", "JCPENNEY", "BURLINGTON", "DICK'S", "DICKS SPORTING", "MICHAELS",
            "HOBBY LOBBY", "JOANN", "STAPLES", "BED BATH", "WAYFAIR", "SHEIN", "TEMU", "APPLE STORE", "NIKE",
            "PETSMART", "PETCO", "CHEWY", "TRACTOR SUPPLY", "BARNES",
        ),
        DefaultCategories.TRANSFER to listOf(
            "PAYMENT THANK YOU", "AUTOPAY", "AUTO PAY", "AUTOMATIC PAYMENT", "ONLINE PAYMENT", "MOBILE PAYMENT",
            "CREDIT CARD PAYMENT", "CREDIT CRD", "CARD PAYMENT", "EPAYMENT", "E-PAYMENT", "TRANSFER", "XFER",
            "ZELLE", "VENMO", "INTERNET PAYMENT", "PAYMENT RECEIVED", "LOAN PAYMENT", "TO SAVINGS", "FROM SAVINGS",
            "TO CHECKING", "FROM CHECKING", "ATM WITHDRAWAL", "CASH WITHDRAWAL",
        ),
    )

    private val compiled: List<Pair<String, List<Regex>>> = keywordTable.map { (cat, words) ->
        cat to words.map { w -> Regex("(^|[^A-Z0-9])" + Regex.escape(w.trim()) + "($|[^A-Z0-9])") }
    }

    /** Stable grouping key: "WEGMANS #0012 ROCHESTER NY" → "WEGMANS ROCHESTER". */
    fun merchantKey(description: String): String {
        var s = description.uppercase().trim()
        var stripped = true
        while (stripped) {
            stripped = false
            for (p in prefixes) {
                if (s.startsWith(p) && s.length > p.length + 2) {
                    s = s.removePrefix(p).trim()
                    stripped = true
                }
            }
        }
        val words = s.replace(Regex("[^A-Z&' ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 || it == "&" }
        return words.take(2).joinToString(" ").ifEmpty { description.trim().uppercase().take(24) }
    }

    fun displayMerchant(description: String): String =
        merchantKey(description).split(" ").joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.titlecase() }
        }

    fun categorize(
        description: String,
        amountCents: Long,
        accountType: AccountType?,
        rules: List<CategoryRule>,
    ): String {
        val up = " " + description.uppercase() + " "
        rules.firstOrNull { !it.deleted && it.pattern.isNotBlank() && up.contains(it.pattern.uppercase()) }
            ?.let { return it.categoryId }
        for ((cat, regexes) in compiled) {
            if (regexes.any { it.containsMatchIn(up) }) {
                // A positive "Amazon" row is a refund in Shopping; a positive payroll row is income, etc.
                return cat
            }
        }
        return when {
            amountCents > 0 && accountType == AccountType.CREDIT_CARD ->
                if (up.contains("PAYMENT")) DefaultCategories.TRANSFER else DefaultCategories.OTHER
            amountCents > 0 -> DefaultCategories.INCOME
            else -> DefaultCategories.OTHER
        }
    }
}
