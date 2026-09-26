package io.github.dovecoteescapee.byedpi.strategy

data class Strategy(
    val id: String,
    val name: String,
    val description: String,
    val args: String,
    val recommendedFor: String = "YouTube, Discord, TG"
)

object StrategyCatalog {
    val strategies = listOf(
        Strategy(
            id = "cascade_10",
            name = "Каскадный сплит (10 шагов + TLSrec)",
            description = "Глубокая фрагментация пакетов на 10 интервалах с разворотом очереди и сплитом TLS записи.",
            args = "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s",
            recommendedFor = "YouTube, Discord"
        ),
        Strategy(
            id = "cascade_10_md5",
            name = "Каскадный сплит 10 + MD5Sig + Fake UDP",
            description = "10 интервалов каскада с подменой контрольной суммы MD5 и фейковыми UDP пакетами.",
            args = "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1",
            recommendedFor = "ТСПУ, YouTube, Discord"
        ),
        Strategy(
            id = "cascade_double_auto",
            name = "Двойной каскад с автопереключением (Auto TLS)",
            description = "Двухуровневый каскад с автоматическим переходом на резервный профиль при ошибках TLS/RST.",
            args = "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1",
            recommendedFor = "YouTube 4K, Ростелеком, Дом.ru"
        ),
        Strategy(
            id = "multilevel_cascade",
            name = "Многоуровневый каскад (Disorder + Split)",
            description = "Чередование сплита и перестановки на возрастающих смещениях от начала SNI.",
            args = "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s",
            recommendedFor = "YouTube, Discord"
        ),
        Strategy(
            id = "double_cascade",
            name = "Двойной каскад",
            description = "Комбинированный каскадный сплит с малыми шагами для сложных DPI-фильтров.",
            args = "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s",
            recommendedFor = "YouTube 4K, Telegram"
        ),
        Strategy(
            id = "progressive_split",
            name = "Прогрессивный мультисплит",
            description = "Последовательное разбиение пакета на 8 сегментов после заголовка SNI.",
            args = "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s",
            recommendedFor = "YouTube, Браузер"
        ),
        Strategy(
            id = "fast_tlsrec",
            name = "Быстрый TLSrec (YouTube / Discord)",
            description = "Разбиение TLS записи с последующей перестановкой фрагментов SNI. Высокая скорость.",
            args = "-s1 -d3+s -r1+s",
            recommendedFor = "Discord, Голос, YouTube"
        ),
        Strategy(
            id = "rhythmic_disoob_tlsrec",
            name = "Ритмичный Disoob + TLSrec + HTTP mod",
            description = "Чередование коротких сплитов, TLS записей и модификаций HTTP-заголовков.",
            args = "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
            recommendedFor = "Discord, Голосовые каналы, Twitch"
        ),
        Strategy(
            id = "oob_cascade_sack",
            name = "OOB каскад + Drop SACK (Auto TLS/RST)",
            description = "Сброс SACK, OOB-байт и глубокая фрагментация с автоподстройкой на ошибки TLS.",
            args = "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1",
            recommendedFor = "МТС, Мегафон, Билайн, Tele2"
        ),
        Strategy(
            id = "fake_ttl8",
            name = "Fake SNI (Google) + TTL 8",
            description = "Отправка фиктивного ClientHello с SNI google.com с последующим сплитом TLS записи.",
            args = "-f-1 -n www.google.com -s2+s -r3 -t4",
            recommendedFor = "ТСПУ обход (все сервисы)"
        ),
        Strategy(
            id = "fake_tlsrec_ttl8",
            name = "Фейк + TLSrec (TTL 8)",
            description = "Фейковый пакет с TTL 8 и фрагментацией TLS записи.",
            args = "-d1 -s1+s -r1+s -f-1 -t8",
            recommendedFor = "ТСПУ, YouTube"
        ),
        Strategy(
            id = "fake_low_ttl",
            name = "Фейк с низким TTL (TTL 2)",
            description = "Фейковый пакет умирает до сервера, сбивая только оборудование ТСПУ.",
            args = "-d1 -s1+s -r1+s -f-1 -t2",
            recommendedFor = "Мобильные операторы"
        ),
        Strategy(
            id = "fake_google_clean",
            name = "Fake SNI Google + TLS split",
            description = "Фейковый заголовок google.com со сплитом TLS записи на 1-м байте.",
            args = "-n www.google.com -f-1 -r1+s",
            recommendedFor = "Discord, YouTube"
        ),
        Strategy(
            id = "sni_google_multisplit",
            name = "Fake SNI Google + Мульти-сплит и OOB",
            description = "Фейковый SNI google.com с каскадным разделением и внедрением OOB-байтов.",
            args = "-n www.google.com -f-1 -s1+s -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-1 -a1 -As -r5 -Mh -s1+s -s3+s -a1",
            recommendedFor = "ТСПУ, YouTube, Мессенджеры"
        ),
        Strategy(
            id = "sni_google_triple_auto",
            name = "Fake SNI Google + Тройной авто-профиль",
            description = "Трёхуровневый адаптивный профиль с фейковым SNI и OOB защитой.",
            args = "-n www.google.com -f-1 -a1 -As -s1+s -a1 -As -s5+s -a1 -As -d3 -q7 -o2 -f-1 -r5 -Mh -a1",
            recommendedFor = "YouTube 1080p/4K, Discord Web"
        ),
        Strategy(
            id = "split50_multi_ttl",
            name = "Мульти-сплит до 60 шагов + Fake TTL 2",
            description = "Глубокое дробление пакета с шагом до 60 байт и фейком с ультракоротким TTL.",
            args = "-d1+s -s50+s -a1 -As -f-1 -t2 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
            recommendedFor = "Мобильный интернет, Сложные блокировки"
        ),
        Strategy(
            id = "oob_fake_split11",
            name = "OOB + Fake SNI + MD5Sig (Split 1-11)",
            description = "Адаптивное разделение первых 11 байт с фейковым SNI и контрольной суммой MD5.",
            args = "-o1 -a1 -At,r,s -f-1 -a1 -At,r,s -d1+s -s11+s -S -a1 -At,r,s -n www.google.com -f-1 -d1+s -s1+s -S -a1",
            recommendedFor = "YouTube, Discord, ТСПУ"
        ),
        Strategy(
            id = "disoob_minimal",
            name = "Минимальный Disoob + Disorder + Split",
            description = "Легковесная комбинация с минимальным оверхедом и высокой пропускной способностью.",
            args = "-d1 -s1 -q1 -a1",
            recommendedFor = "Высокая скорость, Игры, Звонки"
        ),
        Strategy(
            id = "fake_cascade_http",
            name = "Фейк + смещение HTTP Host + TLSrec",
            description = "Специализированная стратегия для одновременного обхода HTTP и HTTPS цензуры.",
            args = "-f-1 -s3+s -a1 -As -d1 -s4+s -s8+h -d6+h -a1 -At,r,s -o2 -r5 -Mh -r6+h -s2+s -s3+s -a1",
            recommendedFor = "Веб-серфинг, Трекеры, Сайты"
        ),
        Strategy(
            id = "ultra_cascade_fibonacci",
            name = "Ультра-каскад 12 шагов (Фибоначчи)",
            description = "Разбиение по возрастающей шкале смещений (1, 2, 3, 5, 8, 12, 17, 23, 30, 40, 50).",
            args = "-d1 -s1+s -d2+s -s3+s -d5+s -s8+s -d12+s -s17+s -d23+s -s30+s -d40+s -s50+s -r1+s -a1",
            recommendedFor = "YouTube, ТСПУ"
        ),
        Strategy(
            id = "fake_low_ttl_md5",
            name = "Fake SNI Google + Низкий TTL 3 + MD5",
            description = "Короткоживущий фейковый пакет умирает перед сервером, оставляя ТСПУ в тупике.",
            args = "-f-1 -t3 -n www.google.com -d1 -s2+s -r1+s -S -a1",
            recommendedFor = "Ростелеком, МТС, Билайн"
        ),
        Strategy(
            id = "oob_multisplit_10",
            name = "OOB + Прогрессивный мультисплит (10 шагов)",
            description = "Внедрение OOB-байта в сочетании с 10-ступенчатым дроблением SNI.",
            args = "-o1 -s1+s -s3+s -s6+s -s10+s -s15+s -s21+s -s28+s -s36+s -s45+s -s55+s -a1",
            recommendedFor = "YouTube, Discord, Telegram"
        ),
        Strategy(
            id = "disorder_reverse_cascade",
            name = "Обратный каскад Disorder",
            description = "Разворот пакетов от хвоста к голове SNI для разрушения анализа потока.",
            args = "-d35+s -d30+s -d25+s -d20+s -d15+s -d10+s -d5+s -d1+s -s1+s -r1+s -a1",
            recommendedFor = "YouTube, ТСПУ"
        ),
        Strategy(
            id = "classic_fake_split",
            name = "Классический Fake + Split",
            description = "Проверенный временем пресет с фейковым пакетом, TTL 8 и перестановкой.",
            args = "-f -1 -t 8 -s 1+s -d 3+s",
            recommendedFor = "Универсально"
        ),
        Strategy(
            id = "combo_oob_disorder",
            name = "Комбо: OOB + Disorder + TLSrec",
            description = "Внедрение OOB-байта в сочетании с перестановкой и разделением TLS записи.",
            args = "-o1 -d1 -r1+s -s1+s -d3+s",
            recommendedFor = "Сложные блокировки"
        ),
        Strategy(
            id = "mixed_split_disorder",
            name = "Смешанный сплит (Disorder + Split)",
            description = "Смешанный сплит до и после SNI с перестановкой пакетов.",
            args = "-d1 -s4 -d8 -s1+s -d5+s -s10+s -d20+s",
            recommendedFor = "YouTube, Discord"
        ),
        Strategy(
            id = "disoob_fake_sack",
            name = "Disoob + Fake + Drop SACK",
            description = "Специальная комбинация Disoob с фейком и сбросом SACK.",
            args = "-q1+s -s29+s -o5+s -f-1 -S",
            recommendedFor = "ТСПУ Ростелеком/МТС/Билайн"
        ),
        Strategy(
            id = "heavy_fake_oob",
            name = "Полный фарш: Fake + OOB + TLSrec",
            description = "Максимально агрессивный режим: OOB-байт, фейковый пакет и сплит TLS записи.",
            args = "-d1 -s1+s -r1+s -e1 -o1+s -f-1 -t2",
            recommendedFor = "Сложные блокировки"
        ),
        Strategy(
            id = "split_50_tlsrec",
            name = "Сплит 50 + TLSrec",
            description = "Сдвинутый сплит на 50 байт со смещением TLS записи.",
            args = "-d1+s -s50+s -r2+s",
            recommendedFor = "Discord, TG"
        ),
        Strategy(
            id = "simple_d1_s3",
            name = "Простой сплит (Disorder 1 + Split 3)",
            description = "Быстрый режим с минимальной нагрузкой на процессор.",
            args = "-d1 -s3+s",
            recommendedFor = "Высокая скорость"
        ),
        Strategy(
            id = "disorder_7_split_2",
            name = "Disorder 7 + Split 2",
            description = "Перестановка на 7-м байте со сплитом на 2-м байте.",
            args = "-d7 -s2",
            recommendedFor = "Telegram, Мессенджеры"
        ),
        Strategy(
            id = "classic_split_disorder",
            name = "Классический Split 1 + Disorder 1",
            description = "Базовая стратегия фрагментации первого байта TCP.",
            args = "-s 1 -d 1",
            recommendedFor = "Легкие блокировки"
        )
    )

    fun getDefaultStrategy(): Strategy = strategies[0]

    fun getStrategyById(id: String): Strategy =
        strategies.find { it.id == id } ?: getDefaultStrategy()
}
