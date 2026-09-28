package com.flixtown.tv.compose

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
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
    private val cyan = Color.rgb(0, 229, 255)
    private val muted = Color.rgb(148, 163, 184)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rail = LinearLayout(activity).apply {
        orientation = 1; background = TvChrome.rail(); setPadding(0, dp(27), 0, dp(27))
    }
    private val content = FrameLayout(activity).apply {
        setPadding(dp(48), dp(27), dp(48), dp(27)); clipChildren = false; clipToPadding = false
    }
    val root = LinearLayout(activity).apply {
        orientation = 0; background = TvChrome.background(); clipChildren = false; clipToPadding = false
        addView(rail, LinearLayout.LayoutParams(dp(96), -1))
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
    private val indicators = mutableMapOf<BrowsePage, View>()
    private val sorts = listOf("Recently added", "Title A–Z", "Top rated")
    private val homeRows = mutableListOf<RecyclerView>()
    private var homeScroll: ScrollView? = null
    private var heroPlay: View? = null
    private var activeGridFilters: Pair<View, View>? = null

    init {
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null && isInside(focused, content)) rail.visibility = View.GONE
            else if (focused != null && isInside(focused, rail)) rail.visibility = View.VISIBLE
        }
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
            val slot = FrameLayout(activity)
            slot.addView(item, FrameLayout.LayoutParams(-1, -1))
            val marker = View(activity).apply { setBackgroundColor(red) }
            slot.addView(marker, FrameLayout.LayoutParams(dp(3), dp(27),
                Gravity.START or Gravity.CENTER_VERTICAL))
            indicators[destination] = marker
            rail.addView(slot, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(6) })
        }
        paintRail()
    }

    fun bind(catalog: BrowseCatalog, onPlay: (TvTitle) -> Unit, onDetails: (TvTitle) -> Unit,
             onRefresh: () -> Unit, onSignOut: () -> Unit) {
        play = onPlay; details = onDetails; refresh = onRefresh; signOut = onSignOut
        if (bound !== catalog) {
            bound = catalog; this.catalog = catalog
            // A late TMDB response must not reset focus or scroll in a movie row/grid.
            val focused = content.findFocus()
            if (page == BrowsePage.Home && focused?.parent !is RecyclerView) render()
        }
    }

    fun dispose() { work?.cancel(); scope.cancel() }

    private fun isInside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun showMenu(): Boolean {
        rail.visibility = View.VISIBLE
        nav[page]?.requestFocus()
        return true
    }

    private fun leftToMenu(view: View) {
        view.setOnKeyListener { _, key, event ->
            key == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                showMenu()
        }
    }

    private fun paintRail() {
        nav.forEach { (destination, item) ->
            val focused = item.hasFocus()
            item.setTextColor(if (focused || destination == page) Color.WHITE else muted)
            item.background = if (focused) TvChrome.action(dp(12).toFloat(), true, 0)
                else ColorDrawable(Color.TRANSPARENT)
            indicators[destination]?.visibility =
                if (focused || destination == page) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun render() {
        work?.cancel(); content.removeAllViews()
        homeRows.clear(); homeScroll = null; heroPlay = null; activeGridFilters = null
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
        homeScroll = scroll
        val column = LinearLayout(activity).apply {
            orientation = 1; clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(36))
        }
        scroll.addView(column); content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        catalog.featured?.let { featured ->
            val hero = FrameLayout(activity).apply { background = TvChrome.panel(dp(14).toFloat()) }
            if (featured.backdropUrl.isNotBlank()) hero.addView(ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .42f
                load(featured.backdropUrl) { size(780, 350); crossfade(false) }
            }, FrameLayout.LayoutParams(-1, -1))
            val info = LinearLayout(activity).apply {
                orientation = 1; gravity = Gravity.BOTTOM
                setPadding(dp(30), dp(18), dp(28), dp(28))
            }
            info.addView(text("FEATURED ON FLIX TOWN", 14, Color.rgb(255, 139, 149), true))
            info.addView(text(featured.name, 33, Color.WHITE, true).apply { maxLines = 2 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            info.addView(text(featured.tags, 16, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val actions = LinearLayout(activity)
            val primary = button("▶  Play") { play(featured) }
            heroPlay = primary
            primary.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> showMenu()
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusHomeRow(0, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
            actions.addView(primary)
            val moreInfo = button("Details") { details(featured) }
            moreInfo.setOnKeyListener { _, key, event ->
                event.action == KeyEvent.ACTION_DOWN && when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusHomeRow(0, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
            actions.addView(moreInfo,
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
            val row = list(LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)).apply {
                adapter = Posters(titles, 176, 262, false)
            }
            homeRows.add(row)
            column.addView(row, LinearLayout.LayoutParams(-1, dp(340)))
        }
    }

    private fun focusHomeRow(rowIndex: Int, position: Int): Boolean {
        val row = homeRows.getOrNull(rowIndex) ?: return true
        val count = row.adapter?.itemCount ?: 0
        if (count == 0) return true
        val targetIndex = position.coerceIn(0, count - 1)
        homeScroll?.scrollTo(0, (row.top - dp(70)).coerceAtLeast(0))
        val visible = row.findViewHolderForAdapterPosition(targetIndex)?.itemView
        if (visible != null) {
            visible.requestFocus()
            return true
        }
        row.scrollToPosition(targetIndex)
        // Focus on the first layout after the target card is attached. One D-pad press is enough
        // even when the corresponding poster is far off-screen in the next row.
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (row.getChildAdapterPosition(view) == targetIndex) {
                    row.removeOnChildAttachStateChangeListener(this)
                    view.requestFocus()
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        }
        row.addOnChildAttachStateChangeListener(listener)
        row.post {
            row.findViewHolderForAdapterPosition(targetIndex)?.itemView?.let {
                row.removeOnChildAttachStateChangeListener(listener)
                it.requestFocus()
            }
        }
        return true
    }

    private fun moveHomeFocus(row: RecyclerView, position: Int, down: Boolean): Boolean {
        val index = homeRows.indexOf(row)
        if (index < 0) return false
        if (down) return focusHomeRow(index + 1, position)
        if (index == 0) {
            homeScroll?.scrollTo(0, 0)
            heroPlay?.requestFocus()
            return true
        }
        return focusHomeRow(index - 1, position)
    }

    private fun grid() {
        val destination = page
        val column = LinearLayout(activity).apply { orientation = 1 }
        content.addView(column, FrameLayout.LayoutParams(-1, -1))
        val posterGrid = list(GridLayoutManager(activity, 4))
        var pendingGridFocus = false
        fun focusFirstPoster(): Boolean {
            val count = posterGrid.adapter?.itemCount
            if (count == null) { pendingGridFocus = true; return true }
            if (count == 0) return true
            pendingGridFocus = false
            val visible = posterGrid.findViewHolderForAdapterPosition(0)?.itemView
            if (visible != null) { visible.requestFocus(); return true }
            val listener = object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (posterGrid.getChildAdapterPosition(view) == 0) {
                        posterGrid.removeOnChildAttachStateChangeListener(this)
                        view.requestFocus()
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) {}
            }
            posterGrid.addOnChildAttachStateChangeListener(listener)
            posterGrid.scrollToPosition(0)
            posterGrid.post {
                posterGrid.findViewHolderForAdapterPosition(0)?.itemView?.let {
                    posterGrid.removeOnChildAttachStateChangeListener(listener)
                    it.requestFocus()
                }
            }
            return true
        }
        column.addView(text(when (destination) {
            BrowsePage.Movies -> "Movies"; BrowsePage.Series -> "TV Shows"; else -> "Watchlist"
        }, 29, Color.WHITE, true), LinearLayout.LayoutParams(-1, dp(48)))
        if (destination != BrowsePage.Watchlist) {
            val categories = if (destination == BrowsePage.Movies)
                catalog.movieCategories else catalog.seriesCategories
            val controls = LinearLayout(activity)
            val categoryButton = button("Categories  ·  " +
                (categories.firstOrNull { it.id == category }?.name ?: "All categories") + "  ▾") {
                val options = listOf("All categories") + categories.map { it.name }
                showPicker("Categories", options) { index ->
                    category = if (index == 0) "" else categories[index - 1].id
                    render()
                }
            }
            categoryButton.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> showMenu()
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusFirstPoster()
                    else -> false
                }
            }
            controls.addView(categoryButton)
            val sortButton = button("Sort by  ·  " + sorts[sort] + "  ▾") {
                showPicker("Sort by", sorts) { index -> sort = index; render() }
            }
            sortButton.setOnKeyListener { _, key, event ->
                event.action == KeyEvent.ACTION_DOWN &&
                    key == KeyEvent.KEYCODE_DPAD_DOWN && focusFirstPoster()
            }
            activeGridFilters = categoryButton to sortButton
            controls.addView(sortButton,
                LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(12) })
            column.addView(controls, LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(6); bottomMargin = dp(34)
            })
        }
        column.addView(posterGrid, LinearLayout.LayoutParams(-1, 0, 1f))
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
            if (page == destination) {
                posterGrid.adapter = Posters(titles, 190, 268, true)
                if (pendingGridFocus) focusFirstPoster()
            }
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
        leftToMenu(input)
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
        val refreshButton = button("Refresh catalog") { refresh() }
        leftToMenu(refreshButton)
        column.addView(refreshButton,
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(20) })
        column.addView(button("Sign out") { signOut() },
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(20) })
        column.addView(text("This product uses the TMDB API but is not endorsed or certified by TMDB.",
            14, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) })
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
            val posterFrame = FrameLayout(activity).apply { clipChildren = false }
            posterFrame.addView(poster, FrameLayout.LayoutParams(-1, -1))
            val focusCover = View(activity).apply {
                background = shape(Color.argb(92, 211, 50, 68), 10, red)
                visibility = View.GONE
            }
            posterFrame.addView(focusCover, FrameLayout.LayoutParams(-1, -1))
            outer.addView(posterFrame, LinearLayout.LayoutParams(-1, dp(height)))
            val name = text("", 16, Color.WHITE, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            outer.addView(name, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })
            outer.setOnFocusChangeListener { view, focused ->
                view.animate().cancel()
                view.animate().scaleX(if (focused) 1.035f else 1f)
                    .scaleY(if (focused) 1.035f else 1f).setDuration(100).start()
                focusCover.visibility = if (focused) View.VISIBLE else View.GONE
            }
            return PosterHolder(outer, poster, name, focusCover)
        }
        override fun onBindViewHolder(holder: PosterHolder, position: Int) {
            val item = titles[position]
            holder.name.text = item.name
            holder.itemView.contentDescription = item.name
            holder.poster.load(item.posterUrl.takeIf { it.isNotBlank() }) {
                size(200, 300); crossfade(false)
            }
            holder.itemView.setOnClickListener { details(item) }
            holder.itemView.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else {
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) false else when (key) {
                        KeyEvent.KEYCODE_DPAD_DOWN ->
                            if (!isGrid) moveHomeFocus(holder.itemView.parent as RecyclerView, position, true)
                            else false
                        KeyEvent.KEYCODE_DPAD_UP ->
                            if (!isGrid) moveHomeFocus(holder.itemView.parent as RecyclerView, position, false)
                            else if (position < 4 && activeGridFilters != null) {
                                activeGridFilters?.first?.requestFocus() == true
                            } else false
                        KeyEvent.KEYCODE_DPAD_LEFT ->
                            (position == 0 || (isGrid && position % 4 == 0)) && showMenu()
                        else -> false
                    }
                }
            }
            holder.itemView.layoutParams = RecyclerView.LayoutParams(
                if (isGrid) -1 else dp(width), dp(height + 82)).apply {
                setMargins(dp(7), dp(6), dp(7), dp(4))
            }
        }
        override fun onViewRecycled(holder: PosterHolder) {
            holder.poster.setImageDrawable(null)
            holder.itemView.animate().cancel()
            holder.itemView.scaleX = 1f; holder.itemView.scaleY = 1f
            holder.cover.visibility = View.GONE
        }
    }
    private class PosterHolder(view: View, val poster: ImageView, val name: TextView, val cover: View) :
        RecyclerView.ViewHolder(view)

    private fun button(title: String, click: () -> Unit) = text(title, 17, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(22), 0, dp(22), 0)
        background = TvChrome.action(dp(10).toFloat(), false, dp(1))
        isFocusable = true; isClickable = true; setOnClickListener { click() }
        setOnFocusChangeListener { view, focused ->
            view.animate().cancel()
            view.animate().scaleX(if (focused) 1.035f else 1f)
                .scaleY(if (focused) 1.035f else 1f).setDuration(100).start()
            background = TvChrome.action(dp(10).toFloat(), focused, dp(1))
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(-2, dp(48)) }
    private fun showPicker(title: String, options: List<String>, selected: (Int) -> Unit) {
        val panel = LinearLayout(activity).apply {
            orientation = 1; background = TvChrome.panel(dp(16).toFloat())
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        panel.addView(text(title, 24, Color.WHITE, true),
            LinearLayout.LayoutParams(-1, dp(45)))
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false
        }
        val entries = LinearLayout(activity).apply { orientation = 1 }
        scroll.addView(entries)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        val dialog = AlertDialog.Builder(activity).setView(panel).create()
        options.forEachIndexed { index, option ->
            val choice = button(option) { dialog.dismiss(); selected(index) }
            entries.addView(choice, LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(8)
            })
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(420), -2)
            entries.getChildAt(0)?.requestFocus()
        }
        dialog.show()
    }
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
