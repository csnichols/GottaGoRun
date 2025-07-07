package com.example.gottagorun

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gottagorun.databinding.ActivityProfileBinding
import com.example.gottagorun.databinding.CalendarDayLayoutBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.ViewContainer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private val runsByDate = mutableMapOf<LocalDate, List<Run>>()
    private var selectedDate: LocalDate? = null
    private val today = LocalDate.now()

    private lateinit var selectedDayRunAdapter: RunAdapter
    private val selectedDayRuns = mutableListOf<Run>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Profile"

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupDayRunsRecyclerView()
        setupCalendar()
        fetchRunData()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun setupDayRunsRecyclerView() {
        selectedDayRunAdapter = RunAdapter(selectedDayRuns) { clickedRun ->
            val intent = Intent(this, RunDetailActivity::class.java).apply {
                putExtra("RUN_ID", clickedRun.documentId)
            }
            startActivity(intent)
        }
        binding.recyclerViewSelectedDayRuns.apply {
            adapter = selectedDayRunAdapter
            layoutManager = LinearLayoutManager(this@ProfileActivity)
        }
    }

    private fun setupCalendar() {
        val currentMonth = YearMonth.now()
        val startMonth = currentMonth.minusMonths(10)
        val endMonth = currentMonth.plusMonths(10)
        val daysOfWeek = daysOfWeek(firstDayOfWeek = DayOfWeek.SUNDAY)

        binding.calendarView.setup(startMonth, endMonth, daysOfWeek.first())
        binding.calendarView.scrollToMonth(currentMonth)

        class DayViewContainer(view: View) : ViewContainer(view) {
            val textView = CalendarDayLayoutBinding.bind(view).calendarDayText
            lateinit var day: CalendarDay

            init {
                view.setOnClickListener {
                    if (day.position == DayPosition.MonthDate) {
                        if (selectedDate != day.date) {
                            val oldDate = selectedDate
                            selectedDate = day.date
                            binding.calendarView.notifyDateChanged(day.date)
                            oldDate?.let { binding.calendarView.notifyDateChanged(it) }
                            updateRunsForSelectedDate()
                        }
                    }
                }
            }
        }

        binding.calendarView.dayBinder = object : MonthDayBinder<DayViewContainer> {
            override fun create(view: View) = DayViewContainer(view)
            override fun bind(container: DayViewContainer, day: CalendarDay) {
                container.day = day
                val textView = container.textView
                textView.text = day.date.dayOfMonth.toString()

                textView.background = null
                textView.setTextColor(Color.WHITE)

                if (day.position == DayPosition.MonthDate) {
                    when {
                        selectedDate == day.date -> {
                            textView.setBackgroundResource(R.drawable.calendar_day_selected_bg)
                            textView.setTextColor(Color.BLACK)
                        }
                        today == day.date -> {
                            textView.setBackgroundResource(R.drawable.calendar_today_bg)
                        }
                        runsByDate.containsKey(day.date) -> {
                            textView.setTextColor(getColor(R.color.md_theme_primaryContainer))
                        }
                        else -> {
                            textView.setTextColor(Color.WHITE)
                        }
                    }
                } else {
                    textView.setTextColor(Color.GRAY)
                }
            }
        }
    }

    private fun updateRunsForSelectedDate() {
        selectedDayRuns.clear()
        val runsForDate = runsByDate[selectedDate]
        if (runsForDate != null) {
            binding.textViewSelectedDayHeader.visibility = View.VISIBLE
            val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
            binding.textViewSelectedDayHeader.text = "Runs on ${selectedDate?.format(formatter)}"
            selectedDayRuns.addAll(runsForDate)
        } else {
            binding.textViewSelectedDayHeader.visibility = View.GONE
        }
        selectedDayRunAdapter.notifyDataSetChanged()
    }

    private fun fetchRunData() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("runs")
            .get()
            .addOnSuccessListener { documents ->
                val allRuns = documents.mapNotNull { document ->
                    val run = document.toObject(Run::class.java)
                    run.documentId = document.id // THIS IS THE FIX
                    run
                }

                runsByDate.clear()
                runsByDate.putAll(allRuns.groupBy {
                    Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                })

                calculateWeeklyMileage(allRuns)
                binding.calendarView.notifyCalendarChanged()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load run data.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun calculateWeeklyMileage(runs: List<Run>) {
        val calendar = Calendar.getInstance()
        val currentWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        val currentYear = calendar.get(Calendar.YEAR)

        val weeklyDistance = runs.filter {
            calendar.timeInMillis = it.timestamp
            calendar.get(Calendar.WEEK_OF_YEAR) == currentWeek && calendar.get(Calendar.YEAR) == currentYear
        }.sumOf { it.totalElevationGain ?: 0.0 } // Safely handle nullable elevation

        binding.textViewWeeklyMileage.text = String.format("This Week: %.2f km", weeklyDistance / 1000.0)
    }
}