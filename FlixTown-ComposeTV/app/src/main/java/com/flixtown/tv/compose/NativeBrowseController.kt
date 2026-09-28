package com.flixtown.tv.compose

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.*

/** Native browse path: remote focus only redraws its own View, never the whole screen. */
internal class NativeBrowseController(private val activity: Activity, private val repo: FlixRepository) {
    private val bg = Color.rgb(7, 8, 11)
    private val surface = Color.rgb(18, 22, 32)
    private val red = Color.rgb(211, 50, 68)
    private val muted = Color.rgb(148, 163, 184)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rail = LinearLayout(activity).apply {
        orientation = 1; setBackgroundColor(surface); setPadding(dp(8), dp(27), dp(8), dp(20))
    }
    private val content = FrameLayout(activity).apply {
        setPadding(dp(22), dp(27), dp(48), dp(27)); clipChildren = false; clipToPadding = false
    }
    val root = LinearLayout(activity).apply {
        orientation = 0; setBackgroundColor(bg); clipChildren = false; clipToPadding = false
        addView(rail, LinearLayout.LayoutParams(dp(88), -1))
        addView(content, LinearLayout.LayoutParams(0, -1, 1f))
    }
    private var catalog = BrowseCatalog()
    private var bound: BrowseCatalog? = null
    private var page = BrowsePage.Home
    private var category = ""
    private var sort = 0
    private var work: Job? = null
    private var play: (TvTitle) -> Unit = {}
    private var details: (TvTitle) -> Unit = {}
    private var refresh: () -> Unit = {}
    private var signOut: () -> Unit = {}
    private val nav = mutableMapOf<BrowsePage, TextView>()
    private val sorts = listOf("Recently added", "Title A–Z", "Top rated")

    init {
        rail.addView(text("FT", 23, red, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, dp(64)))
        BrowsePage.entries.forEach { destination ->
            val icon = when (destination) {
                BrowsePage.Home -> "⌂"; BrowsePage.Search -> "⌕"; BrowsePage.Movies -> "▣"
                BrowsePage.Series -> "▤"; BrowsePage.Watchlist -> "★"; BrowsePage.Settings -> "⚙"
            }
            val item = text(icon, 29, Color.WHITE).apply {
                gravity = Gravity.CENTER; contentDescription = destination.label
                isFocusable = true; isClickable = true
                setOnClickListener {
                    if (page != destination) {
                        page = destination; category = ""; sort = 0; paintRail(); render()
                    }
                }
                setOnFocusChangeListener { view, focused ->
                    view.animate().cancel()
                    view.animate().scaleX(if (focused) 1.04f else 1f)
                        .scaleY(if (focused) 1.04f else 1f).setDuration(100).start()
                    paintRail()
                }
            }
            nav[destination] = item
            rail.addView(item, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(6) })
        }
        paintRail()
    }

    fun bind(catalog: BrowseCatalog, onPlay: (TvTitle) -> Unit, onDetails: (TvTitle) -> Unit,
             onRefresh: () -> Unit, onSignOut: () -> Unit) {
        play = onPlay; details = onDetails; refresh = onRefresh; signOut = onSignOut
        if (bound !== catalog) { bound = catalog; this.catalog = catalog; render() }
    }

    fun dispose() { work?.cancel(); scope.cancel() }

    private fun paintRail() {
        nav.forEach { (destination, item) ->
            val focused = item.hasFocus()
            item.setTextColor(if (focused || destination == page) red else Color.WHITE)
            item.background = shape(
                if (focused) Color.rgb(58, 25, 37) else if (destination == page)
                    Color.rgb(34, 25, 32) else surface, 14)
        }
    }

    private fun render() {
        work?.cancel(); content.removeAllViews()
        when (page) {
            BrowsePage.Home -> home()
            BrowsePage.Movies, BrowsePage.Series, BrowsePage.Watchlist -> grid()
            BrowsePage.Search -> search()
            BrowsePage.Settings -> settings()
        }
    }

    private fun home() {
        val scroll = ScrollView(activity).apply {
            clipToPadding = false; clipChildren = false; isVerticalScrollBarEnabled = false
        }
        val column = LinearLayout(activity).apply {
            orientation = 1; clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(36))
        }
        scroll.addView(column); content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        catalog.featured?.let { featured ->
            val hero = FrameLayout(activity).apply { background = shape(surface, 18) }
            if (featured.backdropUrl.isNotBlank()) hero.addView(ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .42f
                load(featured.backdropUrl) { size(780, 350); crossfade(false) }
            }, FrameLayout.LayoutParams(-1, -1))
            val info = LinearLayout(activity).apply {
                orientation = 1; gravity = Gravity.BOTTOM
                setPadding(dp(30), dp(18), dp(28), dp(28))
            }
            info.addView(text("FEATURED", 14, red, true))
            info.addView(text(featured.name, 33, Color.WHITE, true).apply { maxLines = 2 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            info.addView(text(featured.tags, 16, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val actions = LinearLayout(activity)
            val primary = button("▶  Play") { play(featured) }
            actions.addView(primary)
            actions.addView(button("Details") { details(featured) },
                LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(12) })
            info.addView(actions, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(16) })
            hero.addView(info, FrameLayout.LayoutParams(-1, -1))
            column.addView(hero, LinearLayout.LayoutParams(-1, dp(285)))
            primary.post { if (page == BrowsePage.Home && !root.hasFocus()) primary.requestFocus() }
        }
        catalog.rows.filter { it.second.isNotEmpty() }.forEach { (heading, titles) ->
            column.addView(text(heading, 24, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(28); bottomMargin = dp(10)
                })
            column.addView(list(LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)).apply {
                adapter = Posters(titles, 176, 262, false)
            }, LinearLayout.LayoutParams(-1, dp(340)))
        }
    }

    private fun grid() {
        val destination = page
        val column = LinearLayout(activity).apply { orientation = 1 }
        content.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(text(when (destination) {
            BrowsePage.Movies -> "Movies"; BrowsePage.Series -> "TV Shows"; else -> "Watchlist"
        }, 29, Color.WHITE, true), LinearLayout.LayoutParams(-1, dp(48)))
        if (destination != BrowsePage.Watchlist) {
            val categories = if (destination == BrowsePage.Movies)
                catalog.movieCategories else catalog.seriesCategories
            val controls = LinearLayout(activity)
            controls.addView(button("Categories  ·  " +
                (categories.firstOrNull { it.id == category }?.name ?: "All categories") + "  ▾") {
                val options = listOf("All categories") + categories.map { it.name }
                AlertDialog.Builder(activity).setTitle("Categories")
                    .setItems(options.toTypedArray()) { _, index ->
                        category = if (index == 0) "" else categories[index - 1].id
                        render()
                    }.show()
            })
            controls.addView(button("Sort by  ·  " + sorts[sort] + "  ▾") {
                AlertDialog.Builder(activity).setTitle("Sort by")
                    .setItems(sorts.toTypedArray()) { _, index -> sort = index; render() }.show()
            }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(12) })
            column.addView(controls, LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(6); bottomMargin = dp(22)
            })
        }
        val grid = list(GridLayoutManager(activity, 4))
        column.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val selectedCategory = category; val selectedSort = sort
        work = scope.launch {
            val titles = withContext(Dispatchers.Default) {
                val source = when (destination) {
                    BrowsePage.Movies -> catalog.movies
                    BrowsePage.Series -> catalog.series
                    else -> catalog.movies + catalog.series
                }
                val subset = source.filter {
                    (destination != BrowsePage.Watchlist || repo.inWatchlist(it.id)) &&
                        (selectedCategory.isEmpty() || it.categoryId == selectedCategory)
                }
                when (selectedSort) {
                    1 -> subset.sortedBy { it.name.lowercase() }
                    2 -> subset.sortedByDescending { it.rating }
                    else -> subset.sortedByDescending { it.added }
                }
            }
            if (page == destination) grid.adapter = Posters(titles, 190, 268, true)
        }
    }

    private fun search() {
        val column = LinearLayout(activity).apply { orientation = 1 }
        content.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(text("Search", 29, Color.WHITE, true))
        val input = EditText(activity).apply {
            hint = "Search movies and TV shows"; setHintTextColor(muted)
            setTextColor(Color.WHITE); textSize = 19f; setSingleLine(true)
            background = shape(surface, 12, red); setPadding(dp(20), 0, dp(20), 0)
        }
        column.addView(input, LinearLayout.LayoutParams(-1, dp(58)).apply {
            topMargin = dp(18); bottomMargin = dp(22)
        })
        val results = list(GridLayoutManager(activity, 4))
        column.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                work?.cancel()
                val query = s?.toString()?.trim().orEmpty()
                work = scope.launch {
                    delay(180)
                    val found = withContext(Dispatchers.Default) {
                        if (query.length < 2) emptyList() else
                            (catalog.movies + catalog.series).filter {
                                it.name.contains(query, ignoreCase = true)
                            }.take(120)
                    }
                    if (page == BrowsePage.Search) results.adapter = Posters(found, 190, 268, true)
                }
            }
        })
        input.requestFocus()
    }

    private fun settings() {
        val column = LinearLayout(activity).apply { orientation = 1 }
        content.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(text("Settings", 29, Color.WHITE, true))
        column.addView(button("Refresh catalog") { refresh() },
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(20) })
        column.addView(button("Sign out") { signOut() },
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(20) })
    }

    private fun list(manager: RecyclerView.LayoutManager) = RecyclerView(activity).apply {
        layoutManager = manager
        (manager as? LinearLayoutManager)?.initialPrefetchItemCount = 2
        itemAnimator = null; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        clipToPadding = false; clipChildren = false
        setPadding(dp(8), dp(8), dp(8), dp(18)); setItemViewCacheSize(8)
    }

    private inner class Posters(private val titles: List<TvTitle>, private val width: Int,
        private val height: Int, private val isGrid: Boolean) : RecyclerView.Adapter<PosterHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = titles[position].id.hashCode().toLong()
        override fun getItemCount() = titles.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterHolder {
            val outer = LinearLayout(activity).apply {
                orientation = 1; isFocusable = true; isClickable = true
                clipChildren = false; setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val poster = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(surface, 10); clipToOutline = true
            }
            outer.addView(poster, LinearLayout.LayoutParams(-1, dp(height)))
            val name = text("", 16, Color.WHITE, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            outer.addView(name, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })
            outer.setOnFocusChangeListener { view, focused ->
                view.animate().cancel()
                view.animate().scaleX(if (focused) 1.035f else 1f)
                    .scaleY(if (focused) 1.035f else 1f).setDuration(100).start()
                outer.background = shape(Color.TRANSPARENT, 12,
                    if (focused) red else Color.TRANSPARENT)
            }
            return PosterHolder(outer, poster, name)
        }
        override fun onBindViewHolder(holder: PosterHolder, position: Int) {
            val item = titles[position]
            holder.name.text = item.name
            holder.itemView.contentDescription = item.name
            holder.poster.load(item.posterUrl.takeIf { it.isNotBlank() }) {
                size(200, 300); crossfade(false)
            }
            holder.itemView.setOnClickListener { details(item) }
            holder.itemView.layoutParams = RecyclerView.LayoutParams(
                if (isGrid) -1 else dp(width), dp(height + 82)).apply {
                setMargins(dp(7), dp(6), dp(7), dp(4))
            }
        }
        override fun onViewRecycled(holder: PosterHolder) {
            holder.poster.setImageDrawable(null)
            holder.itemView.animate().cancel()
            holder.itemView.scaleX = 1f; holder.itemView.scaleY = 1f
        }
    }
    private class PosterHolder(view: View, val poster: ImageView, val name: TextView) :
        RecyclerView.ViewHolder(view)

    private fun button(title: String, click: () -> Unit) = text(title, 17, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(22), 0, dp(22), 0)
        background = shape(surface, 24, muted)
        isFocusable = true; isClickable = true; setOnClickListener { click() }
        setOnFocusChangeListener { view, focused ->
            view.animate().cancel()
            view.animate().scaleX(if (focused) 1.035f else 1f)
                .scaleY(if (focused) 1.035f else 1f).setDuration(100).start()
            background = shape(if (focused) red else surface, 24,
                if (focused) Color.WHITE else muted)
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(-2, dp(48)) }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(activity).apply {
            text = value; textSize = size.toFloat(); setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun shape(fill: Int, radius: Int, border: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(radius).toFloat()
            if (border != Color.TRANSPARENT) setStroke(dp(2), border)
        }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
}
