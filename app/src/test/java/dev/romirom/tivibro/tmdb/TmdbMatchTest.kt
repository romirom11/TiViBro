package dev.romirom.tivibro.tmdb

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbMatchTest {
    private fun c(s: String) = TmdbTitle.clean(s)

    // ------------------------------------------------------------------ cleaning

    @Test fun prefixesSayFilmOrSeries() {
        assertEquals(CleanTitle("Ирония судьбы, или С лёгким паром!", null, Kind.FILM), c("х/ф «Ирония судьбы, или С лёгким паром!»"))
        assertEquals(CleanTitle("Тени исчезают в полдень", null, Kind.SERIES), c("Т/с \"Тени исчезают в полдень\""))
        assertEquals(Kind.FILM, c("м/ф Король Лев").kind)
        assertEquals("Король Лев", c("м/ф Король Лев").query)
        assertEquals(Kind.FILM, c("Худ. фильм «Москва слезам не верит»").kind)
        assertEquals("Москва слезам не верит", c("Худ. фильм «Москва слезам не верит»").query)
        assertEquals(Kind.FILM, c("Художній фільм \"Кіборги\"").kind)
        assertEquals("Кіборги", c("Художній фільм \"Кіборги\"").query)
        assertEquals(Kind.FILM, c("Д/ф Земля").kind)
        assertEquals(Kind.SERIES, c("Серіал: Слуга народу").kind)
        assertEquals("Слуга народу", c("Серіал: Слуга народу").query)
    }

    @Test fun aWordThatOnlyStartsLikeAPrefixStays() {
        assertEquals(CleanTitle("Фильмы о войне", null, null), c("Фильмы о войне"))
        assertEquals(CleanTitle("Тсунами", null, null), c("Тсунами"))
    }

    @Test fun seasonAndEpisodeMarkersAreCut() {
        assertEquals(CleanTitle("След", null, Kind.SERIES), c("Т/с «След». 9 сезон, 12 серия"))
        assertEquals(CleanTitle("Family Guy", null, Kind.SERIES), c("Family Guy - сезон 6, серия 8 - Schnurrbart und Sohn"))
        assertEquals(CleanTitle("Friends", null, Kind.SERIES), c("Friends S05E14"))
        assertEquals(CleanTitle("Доктор Хаус", null, Kind.SERIES), c("Доктор Хаус. 3-я серия"))
        assertEquals(CleanTitle("Сваты", null, Kind.SERIES), c("Сваты · 3"))
        assertEquals(CleanTitle("Kitchen", null, Kind.SERIES), c("Kitchen (12 серия)"))
        assertEquals(CleanTitle("The Simpsons", null, Kind.SERIES), c("The Simpsons Season 30 Episode 4"))
        assertEquals(CleanTitle("Tatort", null, Kind.SERIES), c("Tatort, Folge 1023"))
    }

    @Test fun yearIsKept() {
        assertEquals(CleanTitle("Sense and Sensibility", 1995, null), c("Sense and Sensibility (1995)"))
        assertEquals(CleanTitle("Beast", 2026, null), c("Beast (2026)"))
        assertEquals(CleanTitle("Интерстеллар", 2014, Kind.FILM), c("х/ф Интерстеллар (2014) 16+"))
        // a title that is a year stays a title
        assertEquals(CleanTitle("1917", null, null), c("1917"))
        assertEquals(CleanTitle("Blade Runner 2049", null, null), c("Blade Runner 2049"))
    }

    @Test fun ageRatingGoes() {
        assertEquals("Голодные игры", c("Голодные игры (16+)").query)
    }

    @Test fun genericTitlesAreNotSearched() {
        assertFalse(TmdbTitle.searchable(c("Новости")))
        assertFalse(TmdbTitle.searchable(c("Погода")))
        assertFalse(TmdbTitle.searchable(c("х/ф")))
        assertFalse(TmdbTitle.searchable(c("Football")))
        assertTrue(TmdbTitle.searchable(c("The Bourne Supremacy (2004)")))
    }

    @Test fun similarity() {
        assertEquals(1.0, TmdbTitle.similarity("Ёлки", "Елки"), 0.0)
        assertEquals(1.0, TmdbTitle.similarity("Fast & Furious", "Fast and Furious"), 0.0)
        assertTrue(TmdbTitle.similarity("The Bourne Supremacy", "Bourne Supremacy") > 0.75)
        assertTrue(TmdbTitle.similarity("Friends", "Fiends") < 0.9)
    }

    // ------------------------------------------------------------------ matching, against mocked TMDB answers

    private fun results(json: String, type: String? = null) = Tmdb.parseResults(JSONObject(json), type)

    private val bourne = """{"page":1,"results":[
        {"id":2502,"media_type":"movie","title":"The Bourne Supremacy","original_title":"The Bourne Supremacy","release_date":"2004-07-23","vote_count":9000,"vote_average":7.3,"poster_path":"/p.jpg","backdrop_path":"/b.jpg","overview":"x"},
        {"id":9,"media_type":"person","name":"Matt Damon"},
        {"id":77,"media_type":"movie","title":"The Bourne Supremacy: Making Of","original_title":"x","release_date":"2005-01-01","vote_count":3,"vote_average":5.0}
    ]}"""

    @Test fun filmWithYearMatches() {
        val hit = TmdbMatcher.pick(c("The Bourne Supremacy (2004)"), results(bourne), 110)
        assertEquals(2502, hit?.id)
        assertEquals("/b.jpg", hit?.backdrop)
    }

    @Test fun wrongYearIsRejected() {
        assertNull(TmdbMatcher.pick(c("The Bourne Supremacy (1990)"), results(bourne), 110))
    }

    @Test fun anUnmarkedTitleIsNotAFilm() {
        // guides mark films with a year or «х/ф»; without either, a film's title is too weak a match
        assertNull(TmdbMatcher.pick(c("The Bourne Supremacy"), results(bourne), 110))
        assertEquals(2502, TmdbMatcher.pick(c("х/ф The Bourne Supremacy"), results(bourne), 110)?.id)
        // BBC One's football results, seen in a real guide, against the 2018 film of the same name
        val finalScore = results("""{"results":[{"id":421658,"media_type":"movie","title":"Final Score","original_title":"Final Score","release_date":"2018-08-31","vote_count":700,"vote_average":6.0}]}""")
        assertNull(TmdbMatcher.pick(c("Final Score"), finalScore, 100))
    }

    @Test fun aShowNeedsAShowSizedSlot() {
        val r = results("""{"results":[{"id":456,"media_type":"tv","name":"The Simpsons","original_name":"The Simpsons","first_air_date":"1989-12-17","vote_count":10000,"vote_average":8.0}]}""")
        assertEquals(456, TmdbMatcher.pick(c("The Simpsons"), r, 30)?.id)
        assertNull(TmdbMatcher.pick(c("The Simpsons"), r, 240))
    }

    @Test fun seriesPrefixWantsATvResult() {
        val r = results("""{"results":[{"id":1,"title":"След","original_title":"След","release_date":"2007-01-01","vote_count":500,"vote_average":6.0}]}""", "movie")
        assertNull(TmdbMatcher.pick(c("Т/с «След». 9 сезон"), r, 50))
        val tv = results("""{"results":[{"id":2,"name":"След","original_name":"След","first_air_date":"2007-03-12","vote_count":40,"vote_average":5.9,"poster_path":"/s.jpg"}]}""", "tv")
        assertEquals(2, TmdbMatcher.pick(c("Т/с «След». 9 сезон"), tv, 50)?.id)
    }

    @Test fun twoEquallyKnownCandidatesMeanNoMatch() {
        val r = results("""{"results":[
            {"id":2316,"media_type":"tv","name":"The Office","original_name":"The Office","first_air_date":"2005-03-24","vote_count":4000,"vote_average":8.6},
            {"id":2996,"media_type":"tv","name":"The Office","original_name":"The Office","first_air_date":"2001-07-09","vote_count":1500,"vote_average":7.9}
        ]}""")
        assertNull(TmdbMatcher.pick(c("The Office"), r, 30))
    }

    @Test fun aClearFavouriteWins() {
        val r = results("""{"results":[
            {"id":1668,"media_type":"tv","name":"Friends","original_name":"Friends","first_air_date":"1994-09-22","vote_count":8000,"vote_average":8.4,"backdrop_path":"/f.jpg"},
            {"id":5,"media_type":"tv","name":"Friends","original_name":"Friends","first_air_date":"1979-01-01","vote_count":12,"vote_average":6.0}
        ]}""")
        assertEquals(1668, TmdbMatcher.pick(c("Friends"), r, 30)?.id)
    }

    @Test fun aLooseTitleWithoutBackingIsRejected() {
        val r = results("""{"results":[{"id":3,"media_type":"movie","title":"Homes Under the Hammer Movie","original_title":"x","release_date":"2010-01-01","vote_count":100,"vote_average":5.0}]}""")
        assertNull(TmdbMatcher.pick(c("Homes Under the Hammer"), r, 60))
    }

    @Test fun obscureExactMatchWithoutBackingIsRejected() {
        val r = results("""{"results":[{"id":4,"media_type":"movie","title":"Escape to the Country","original_title":"Escape to the Country","release_date":"2014-01-01","vote_count":2,"vote_average":4.0}]}""")
        assertNull(TmdbMatcher.pick(c("Escape to the Country"), r, 90))
    }

    @Test fun translatedTitleMatchesThroughTheOriginal() {
        val r = results("""{"results":[{"id":157336,"title":"Інтерстеллар","original_title":"Interstellar","release_date":"2014-11-05","vote_count":30000,"vote_average":8.4}]}""", "movie")
        assertEquals(157336, TmdbMatcher.pick(c("х/ф Interstellar"), r, 160)?.id)
        assertEquals(157336, TmdbMatcher.pick(c("х/ф «Інтерстеллар»"), r, 160)?.id)
    }

    @Test fun languages() {
        assertEquals("uk-UA", Tmdb.tmdbLang("uk"))
        assertEquals("kk-KZ", Tmdb.tmdbLang("kk"))
        assertEquals("en-US", Tmdb.tmdbLang("fr"))
        assertEquals(listOf("ru-RU", "en-US"), Tmdb.fallbacks("kk-KZ"))
        assertEquals(emptyList<String>(), Tmdb.fallbacks("en-US"))
    }
}
