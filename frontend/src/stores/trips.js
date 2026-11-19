import { defineStore } from 'pinia'
import {
  listItineraries as apiList,
  getItinerary as apiDetail,
  deleteItinerary as apiDelete,
  pinItinerary as apiPin,
  updateItinerary as apiUpdate
} from '../api/xingji'

/**
 * 「我的行程」页 store —— 左侧栏是行程列表（按标题呈现，像大模型的历史会话栏），
 * 右侧是选中那条行程的完整安排。列表来自 GET /itinerary/list（每项仅 id/title/status/days/startDate/budget），
 * 详情来自 GET /itinerary/{id}（ItineraryDetailVO = { itinerary, days:[{day,nodes[]}] }）。
 * 这里不存 token，登录态仍由 auth store / api 层负责；401 交给全局回调弹回登录。
 */
export const useTripsStore = defineStore('trips', {
  state: () => ({
    list: [], // Itinerary[]（列表态）
    listLoading: false,
    listError: '',
    selectedId: null,
    detail: null, // ItineraryDetailVO
    detailLoading: false,
    detailError: '',
    collapsed: false // 左侧栏是否收起（抽屉）
  }),

  actions: {
    toggleCollapse() {
      this.collapsed = !this.collapsed
    },

    async loadList() {
      this.listLoading = true
      this.listError = ''
      try {
        const rows = await apiList()
        this.list = Array.isArray(rows) ? rows : []
      } catch (e) {
        if (e.kind !== 'unauthorized') this.listError = e.message || '加载行程失败'
      } finally {
        this.listLoading = false
      }
    },

    /** 选中某条行程并拉取它的完整安排 */
    async select(id) {
      if (!id) return
      this.selectedId = id
      this.detail = null
      this.detailError = ''
      this.detailLoading = true
      try {
        this.detail = await apiDetail(id)
      } catch (e) {
        if (e.kind !== 'unauthorized') this.detailError = e.message || '加载行程详情失败'
      } finally {
        this.detailLoading = false
      }
    },

    /**
     * 删除一条行程（软删除，列表即刻不可见）；删的是当前选中的则一并清空选中态。
     * 错误向上抛给 UI 提示；二次确认由调用方负责。
     */
    async remove(id) {
      if (!id) return
      await apiDelete(id)
      if (this.selectedId === id) {
        this.selectedId = null
        this.detail = null
      }
      await this.loadList()
    },

    /** 批量删除（多选模式用）：逐条软删除，返回失败条数；删到当前选中的会清空选中态 */
    async removeMany(ids) {
      const list = (ids || []).filter(Boolean)
      if (!list.length) return { total: 0, failed: 0 }
      let failed = 0
      for (const id of list) {
        try {
          await apiDelete(id)
          if (this.selectedId === id) {
            this.selectedId = null
            this.detail = null
          }
        } catch (e) {
          if (e.kind === 'unauthorized') throw e
          failed++
        }
      }
      await this.loadList()
      return { total: list.length, failed }
    },

    /** 重命名：后端 update 是整体覆盖，列表项里带的基础字段一并全量回传 */
    async rename(id, title) {
      const t = this.list.find((x) => x.id === id)
      if (!t) return
      await apiUpdate(id, {
        title: (title || '').trim() || t.title,
        startDate: t.startDate ? String(t.startDate).slice(0, 10) : null,
        days: t.days ?? null,
        budget: t.budget ?? null
      })
      await this.loadList()
    },

    /** 置顶/取消置顶后刷新列表（列表接口已按置顶在前排序） */
    async togglePin(id) {
      const t = this.list.find((x) => x.id === id)
      if (!t) return
      await apiPin(id, !t.pinned)
      await this.loadList()
    }
  }
})
