package com.iptv807.tv

/**
 * 港澳台专项清单（55台）—— 3大子分组（香港 / 澳门 / 台湾）
 * 存 tid+id，播放Url每次实时解密（token 每次都换）
 */
object ChannelData {
    data class Channel(val num: Int, val name: String, val group: String, val tid: String, val id: String)
    data class Group(val name: String, val startNum: Int, val count: Int)

    fun load(): List<Channel> = listOf(
        Channel(1, "HOY TV", "🌏 香港", "tv", "43"),
        Channel(2, "HOY国际财经台", "🌏 香港", "tv", "41"),
        Channel(3, "HOY资讯台", "🌏 香港", "tv", "42"),
        Channel(4, "NOW新闻台", "🌏 香港", "tv", "36"),
        Channel(5, "NOW财经台", "🌏 香港", "tv", "37"),
        Channel(6, "TVB Plus", "🌏 香港", "tv", "26"),
        Channel(7, "TVB无线新闻台", "🌏 香港", "tv", "25"),
        Channel(8, "TVB明珠台", "🌏 香港", "tv", "27"),
        Channel(9, "TVB翡翠台", "🌏 香港", "tv", "24"),
        Channel(10, "ViuTV", "🌏 香港", "tv", "38"),
        Channel(11, "ViuTV6", "🌏 香港", "tv", "39"),
        Channel(12, "凤凰卫视中文台", "🌏 香港", "tv", "20"),
        Channel(13, "凤凰卫视资讯台", "🌏 香港", "tv", "21"),
        Channel(14, "凤凰卫视香港台", "🌏 香港", "tv", "22"),
        Channel(15, "港台电视31", "🌏 香港", "tv", "45"),
        Channel(16, "港台电视32", "🌏 香港", "gt", "27"),
        Channel(17, "香港卫视HKS", "🌏 香港", "gt", "30"),
        Channel(18, "香港有线18台", "🌏 香港", "gt", "12"),
        Channel(19, "香港有线18台(备)", "🌏 香港", "gt", "13"),
        Channel(20, "香港有线新闻台", "🌏 香港", "tv", "29"),
        Channel(21, "澳視澳門", "🌏 澳门", "gt", "38"),
        Channel(22, "澳视综艺", "🌏 澳门", "gt", "40"),
        Channel(23, "澳视资讯", "🌏 澳门", "gt", "37"),
        Channel(24, "澳門Macau", "🌏 澳门", "gt", "39"),
        Channel(25, "澳門卫视", "🌏 澳门", "gt", "36"),
        Channel(26, "澳门莲花", "🌏 澳门", "gt", "35"),
        Channel(27, "DAZN 1", "🌏 台湾", "tv", "80"),
        Channel(28, "DAZN 2", "🌏 台湾", "tv", "81"),
        Channel(29, "ELEVEN 1（DAZN 1）", "🌏 台湾", "ty", "24"),
        Channel(30, "ELEVEN 2（DAZN 2）", "🌏 台湾", "ty", "25"),
        Channel(31, "TVBS HD", "🌏 台湾", "tv", "65"),
        Channel(32, "TVBS新闻台", "🌏 台湾", "tv", "63"),
        Channel(33, "三立新闻台", "🌏 台湾", "tv", "54"),
        Channel(34, "东森新闻台", "🌏 台湾", "tv", "50"),
        Channel(35, "东森财经新闻台", "🌏 台湾", "gt", "45"),
        Channel(36, "东森超视", "🌏 台湾", "tv", "98"),
        Channel(37, "中天新闻台", "🌏 台湾", "tv", "49"),
        Channel(38, "中视HD", "🌏 台湾", "tv", "68"),
        Channel(39, "中视新闻台", "🌏 台湾", "tv", "52"),
        Channel(40, "华视HD", "🌏 台湾", "tv", "71"),
        Channel(41, "华视新闻台", "🌏 台湾", "tv", "57"),
        Channel(42, "台视HD", "🌏 台湾", "tv", "69"),
        Channel(43, "台视新闻台", "🌏 台湾", "tv", "53"),
        Channel(44, "寰宇新闻台", "🌏 台湾", "tv", "59"),
        Channel(45, "寰宇新闻台湾台", "🌏 台湾", "tv", "60"),
        Channel(46, "民视HD", "🌏 台湾", "tv", "70"),
        Channel(47, "民视新闻台", "🌏 台湾", "tv", "56"),
        Channel(48, "爱尔达体育1台", "🌏 台湾", "tv", "83"),
        Channel(49, "爱尔达体育2台", "🌏 台湾", "tv", "84"),
        Channel(50, "緯來體育台", "🌏 台湾", "tv", "77"),
        Channel(51, "纬来日本台", "🌏 台湾", "tv", "78"),
        Channel(52, "纬来育乐台", "🌏 台湾", "tv", "79"),
        Channel(53, "镜电视新闻台", "🌏 台湾", "tv", "58"),
        Channel(54, "非凡新闻", "🌏 台湾", "tv", "62"),
        Channel(55, "华丽翡翠台", "🌏 其他", "tv", "28")
    )

    fun groups(): List<Group> = listOf(
        Group("🌏 香港", 1, 20),
        Group("🌏 澳门", 21, 6),
        Group("🌏 台湾", 27, 28),
        Group("🌏 其他", 55, 1)
    )
}
