package com.example.myreceipts

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.example.myreceipts.data.AppPreferences
import com.example.myreceipts.data.AppRepository
import com.example.myreceipts.data.AuthUser
import com.example.myreceipts.data.StaffManager
import com.example.myreceipts.data.StaffReceipt
import com.google.android.material.bottomnavigation.BottomNavigationView

class StaffMainActivity : AppCompatActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var repository: AppRepository
    private lateinit var user: AuthUser
    private lateinit var content: FrameLayout
    private lateinit var bottomNavigation: BottomNavigationView
    private var selectedReceiptFilter = R.id.chipAll

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        repository = AppRepository(this)

        val userId = intent.getLongExtra(EXTRA_USER_ID, -1L)
            .takeIf { it > 0 }
            ?: preferences.getSession()?.userId?.toLongOrNull()
        user = userId?.let(repository::getUser) ?: run {
            preferences.clearSession()
            finish()
            return
        }
        if (user.role != ROLE_ADMIN && user.role != ROLE_MANAGER) {
            finish()
            return
        }

        setContentView(R.layout.activity_staff_main)
        content = findViewById(R.id.fragmentContainer)
        bottomNavigation = findViewById(R.id.bottomNav)
        bottomNavigation.menu.clear()
        bottomNavigation.inflateMenu(
            if (user.role == ROLE_ADMIN) R.menu.menu_bottom_admin else R.menu.menu_bottom_manager
        )
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_accounts -> showManagers()
                R.id.nav_receipts -> showReceipts()
                R.id.nav_profile -> showProfile()
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        bottomNavigation.selectedItemId =
            if (user.role == ROLE_ADMIN) R.id.nav_accounts else R.id.nav_receipts
    }

    private fun showManagers() {
        val screen = showLayout(R.layout.activity_admin_managers)
        val list = screen.findViewById<RecyclerView>(R.id.rvManagers)
        val empty = screen.findViewById<TextView>(R.id.tvEmpty)
        val search = screen.findViewById<EditText>(R.id.etSearch)
        val adapter = ManagersAdapter()
        list.adapter = adapter
        fun refresh() {
            val query = search.text.toString().trim()
            val managers = repository.getManagers().filter {
                query.isEmpty() ||
                    it.fullName.contains(query, ignoreCase = true) ||
                    it.email.contains(query, ignoreCase = true) ||
                    it.companyName.contains(query, ignoreCase = true)
            }
            adapter.submit(managers)
            empty.visibility = if (managers.isEmpty()) View.VISIBLE else View.GONE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        screen.findViewById<View>(R.id.btnCreateManager).setOnClickListener {
            showManagerCreate()
        }
        refresh()
    }

    private fun showManagerCreate() {
        val screen = showLayout(R.layout.activity_admin_manager_edit)
        screen.findViewById<View>(R.id.btnBack).setOnClickListener { showManagers() }
        screen.findViewById<View>(R.id.btnSave).setOnClickListener {
            val name = screen.findViewById<EditText>(R.id.etName).text.toString()
            val email = screen.findViewById<EditText>(R.id.etEmail).text.toString()
            val phone = screen.findViewById<EditText>(R.id.etPhone).text.toString()
            val company = screen.findViewById<EditText>(R.id.etCompany).text.toString()
            val password = screen.findViewById<EditText>(R.id.etPassword).text.toString()
            try {
                repository.createManager(user.id, name, email, phone, company, password)
                Toast.makeText(this, "Аккаунт менеджера создан", Toast.LENGTH_SHORT).show()
                showManagers()
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "Не удалось создать аккаунт", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showReceipts() {
        val screen = showLayout(R.layout.activity_manager_receipts)
        screen.findViewById<TextView>(R.id.tvTitle).text =
            if (user.role == ROLE_ADMIN) "Все квитанции" else getString(R.string.manager_receipts_title)
        screen.findViewById<TextView>(R.id.tvCompany).text =
            if (user.role == ROLE_ADMIN) "Все управляющие компании"
            else repository.getCompanyName(user.id).orEmpty()

        val list = screen.findViewById<RecyclerView>(R.id.rvReceipts)
        val empty = screen.findViewById<TextView>(R.id.tvEmpty)
        val search = screen.findViewById<EditText>(R.id.etSearch)
        val adapter = ReceiptsAdapter { receipt -> showStatusDialog(receipt) }
        list.adapter = adapter

        val chipGroup = screen.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupFilter)
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedReceiptFilter = checkedIds.firstOrNull() ?: R.id.chipAll
            refreshReceipts(adapter, empty, search.text.toString())
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshReceipts(adapter, empty, s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshReceipts(adapter, empty, "")
    }

    private fun refreshReceipts(adapter: ReceiptsAdapter, empty: TextView, query: String) {
        val companyId = if (user.role == ROLE_MANAGER) {
            repository.getCompanyId(user.id)
        } else {
            null
        }
        val scopedReceipts = if (user.role == ROLE_MANAGER && companyId == null) {
            emptyList()
        } else {
            repository.getReceipts(companyId)
        }
        val receipts = scopedReceipts.filter { receipt ->
            val matchesStatus = when (selectedReceiptFilter) {
                R.id.chipPaid -> receipt.status == "paid"
                R.id.chipUnpaid -> receipt.status == "unpaid"
                else -> true
            }
            val matchesQuery = query.isBlank() ||
                receipt.address.contains(query, ignoreCase = true) ||
                receipt.accountNumber.contains(query, ignoreCase = true) ||
                receipt.payerName.contains(query, ignoreCase = true)
            matchesStatus && matchesQuery
        }
        adapter.submit(receipts)
        empty.visibility = if (receipts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showStatusDialog(receipt: StaffReceipt) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_status, null)
        val statusGroup = dialogView.findViewById<RadioGroup>(R.id.rgStatus)
        val comment = dialogView.findViewById<EditText>(R.id.etComment)
        dialogView.findViewById<TextView>(R.id.tvReceiptSummary).text =
            "${receipt.address} · ${receipt.period} · ${formatMoney(receipt.totalKop)}"
        statusGroup.check(if (receipt.status == "paid") R.id.rbPaid else R.id.rbUnpaid)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val newStatus = if (statusGroup.checkedRadioButtonId == R.id.rbPaid) "paid" else "unpaid"
            try {
                repository.setReceiptStatus(user.id, receipt.id, newStatus, comment.text.toString())
                dialog.dismiss()
                showReceipts()
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "Не удалось изменить статус", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showProfile() {
        val screen = showLayout(R.layout.fragment_staff_profile)
        screen.findViewById<TextView>(R.id.tvName).text = user.fullName
        screen.findViewById<TextView>(R.id.tvEmail).text = user.email
        screen.findViewById<TextView>(R.id.tvAvatar).text =
            user.fullName.firstOrNull()?.uppercase() ?: ""
        screen.findViewById<TextView>(R.id.tvRole).setText(
            if (user.role == ROLE_ADMIN) R.string.role_admin else R.string.role_manager
        )
        screen.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switchNotifications
        ).apply {
            isChecked = preferences.notificationsEnabled
            setOnCheckedChangeListener { _, checked -> preferences.notificationsEnabled = checked }
        }
        screen.findViewById<View>(R.id.btnLogout).setOnClickListener {
            preferences.clearSession()
            startActivity(android.content.Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun showLayout(layoutId: Int): View {
        content.removeAllViews()
        return LayoutInflater.from(this).inflate(layoutId, content, true).let {
            content.getChildAt(0)
        }
    }

    private fun formatMoney(amountKop: Long): String =
        "${amountKop / 100} ₽ ${"%02d".format(amountKop % 100)}"

    private inner class ManagersAdapter : RecyclerView.Adapter<ManagersAdapter.Holder>() {
        private var items = emptyList<StaffManager>()

        fun submit(managers: List<StaffManager>) {
            items = managers
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_manager, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val manager = items[position]
            holder.avatar.text = manager.fullName.firstOrNull()?.uppercase() ?: ""
            holder.name.text = manager.fullName
            holder.company.text = "${manager.companyName} · ${manager.email}"
            holder.status.setText(
                if (manager.active) R.string.manager_status_active else R.string.manager_status_blocked
            )
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.tvAvatar)
            val name: TextView = view.findViewById(R.id.tvName)
            val company: TextView = view.findViewById(R.id.tvCompany)
            val status: TextView = view.findViewById(R.id.tvStatus)
        }
    }

    private inner class ReceiptsAdapter(
        private val onStatusClick: (StaffReceipt) -> Unit
    ) : RecyclerView.Adapter<ReceiptsAdapter.Holder>() {
        private var items = emptyList<StaffReceipt>()

        fun submit(receipts: List<StaffReceipt>) {
            items = receipts
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_receipt, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val receipt = items[position]
            holder.address.text = receipt.address
            holder.meta.text = "${receipt.period} · лиц. счёт ${receipt.accountNumber}"
            holder.amount.text = formatMoney(receipt.totalKop)
            holder.status.setText(if (receipt.status == "paid") R.string.status_paid else R.string.status_unpaid)
            holder.manual.visibility = if (receipt.isManual) View.VISIBLE else View.GONE
            holder.changeStatus.setOnClickListener { onStatusClick(receipt) }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val address: TextView = view.findViewById(R.id.tvAddress)
            val meta: TextView = view.findViewById(R.id.tvMeta)
            val amount: TextView = view.findViewById(R.id.tvAmount)
            val status: TextView = view.findViewById(R.id.tvStatus)
            val manual: TextView = view.findViewById(R.id.tvManualFlag)
            val changeStatus: View = view.findViewById(R.id.btnChangeStatus)
        }
    }

    private companion object {
        const val EXTRA_USER_ID = "staff_user_id"
        const val ROLE_ADMIN = "admin"
        const val ROLE_MANAGER = "manager"
    }
}
