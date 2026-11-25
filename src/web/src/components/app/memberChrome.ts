// The member's chrome — the signed-in app's sidebar, top bar, notices, operator footer, audio bar and shortcut overview
// (`MemberChrome.vue`) — is a chunk of its own (inbox #1310): a link guest's share page draws none of it, so the entry
// it loads does not carry it. One loader for both who need it: `AppShell` draws it (`defineAsyncComponent`), and the
// router enters a page of the signed-in app only once it is here (`appPage`), so the page and its chrome draw together.
export const loadMemberChrome = () => import('./MemberChrome.vue')
