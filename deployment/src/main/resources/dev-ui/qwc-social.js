import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import '@vaadin/text-field';
import '@vaadin/text-area';
import '@vaadin/select';
import '@vaadin/button';

/**
 * Social: the active networks and what they can do, what adapters contribute (Bluesky scope needed vs configured),
 * the last posts with per-network state, and a form that publishes a test post (dev mode only).
 */
export class QwcSocial extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 1rem; padding: 1rem; }
        h3 { margin: 0; font-size: var(--lumo-font-size-m); }
        dl { display: grid; grid-template-columns: max-content auto; gap: 0.2rem 0.75rem; margin: 0; }
        dt { color: var(--lumo-contrast-60pct); }
        dd { margin: 0; overflow-wrap: anywhere; }
        table { border-collapse: collapse; width: 100%; font-size: var(--lumo-font-size-s); }
        th, td { text-align: left; padding: 0.25rem 0.5rem; border-bottom: 1px solid var(--lumo-contrast-10pct); overflow-wrap: anywhere; vertical-align: top; }
        .row { display: flex; gap: 0.5rem; align-items: flex-end; flex-wrap: wrap; }
        .muted { color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s); }
        .error { color: var(--lumo-error-text-color); }
        .ok { color: var(--lumo-success-text-color); }
        .warn { color: var(--lumo-warning-text-color); }
        code { overflow-wrap: anywhere; }
        vaadin-text-area { width: 100%; }
    `;

    static properties = {
        _adapters: { state: true },
        _contributions: { state: true },
        _recent: { state: true },
        _error: { state: true },
        _account: { state: true },
        _text: { state: true },
        _kind: { state: true },
        _visibility: { state: true },
        _sending: { state: true },
        _outcome: { state: true },
    };

    constructor() {
        super();
        this._adapters = null;
        this._contributions = [];
        this._recent = null;
        this._error = null;
        this._account = '';
        this._text = '';
        this._kind = 'NOTE';
        this._visibility = 'PUBLIC';
        this._sending = false;
        this._outcome = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    _fail(name) {
        return e => { this._error = (e && e.error && e.error.message) || (name + '() failed'); };
    }

    _load() {
        this._error = null;
        this.jsonRpc.getAdapters().then(r => { this._adapters = r.result; }).catch(this._fail('getAdapters'));
        this.jsonRpc.getContributions().then(r => { this._contributions = r.result; }).catch(this._fail('getContributions'));
        this.jsonRpc.getRecent().then(r => { this._recent = r.result; }).catch(this._fail('getRecent'));
    }

    render() {
        return html`
            ${this._error ? html`<span class="error">${this._error}</span>` : ''}
            <h3>Networks <vaadin-button theme="tertiary small" @click=${this._load}>Refresh</vaadin-button></h3>
            ${this._renderAdapters()}
            ${(this._contributions || []).map(c => this._renderContribution(c))}
            <h3>Publish a test post</h3>
            ${this._renderForm()}
            <h3>Recent posts</h3>
            ${this._renderRecent()}
        `;
    }

    _renderAdapters() {
        const a = this._adapters;
        if (!a) {
            return html`<span>Loading...</span>`;
        }
        if (a.length === 0) {
            return html`<span class="warn">No SocialAdapter bean: add quarkus-social-activitypub or quarkus-social-atproto.</span>`;
        }
        return html`
            <table>
                <thead><tr><th>Network</th><th>Visibilities</th><th>Kinds</th><th>Edits</th><th>Deletes</th>
                    <th>Content warnings</th><th>Text limit</th><th>HTML</th><th>Interactions</th><th>Status</th></tr></thead>
                <tbody>${a.map(r => html`
                    <tr><td>${r.network}</td>
                        <td>${r.capabilities.visibilities.join(', ')}</td>
                        <td>${r.capabilities.kinds.join(', ')}</td>
                        <td>${this._yes(r.capabilities.edits)}</td>
                        <td>${this._yes(r.capabilities.deletes)}</td>
                        <td>${this._yes(r.capabilities.contentWarnings)}</td>
                        <td>${r.capabilities.maxTextGraphemes == null ? 'none' : r.capabilities.maxTextGraphemes + ' graphemes'}</td>
                        <td>${this._yes(r.capabilities.html)}</td>
                        <td>${this._yes(r.capabilities.interactions)}</td>
                        <td>${r.unavailableReason ? html`<span class="warn">${r.unavailableReason}</span>` : html`<span class="ok">active</span>`}</td></tr>`)}
                </tbody>
            </table>`;
    }

    _yes(b) {
        return b ? 'yes' : 'no';
    }

    _renderContribution(c) {
        const entries = Object.entries(c.data || {});
        return html`
            <h3>${c.title}</h3>
            <dl>${entries.map(([k, v]) => html`<dt>${k}</dt><dd>${this._value(k, v)}</dd>`)}</dl>`;
    }

    _value(key, v) {
        if (typeof v === 'boolean') {
            return html`<span class=${v ? 'ok' : 'warn'}>${v ? 'yes' : 'no'}</span>`;
        }
        return html`<code>${String(v)}</code>`;
    }

    _renderForm() {
        return html`
            <div class="row">
                <vaadin-text-field label="Account" placeholder="alice" .value=${this._account}
                    @value-changed=${e => { this._account = e.detail.value; }}></vaadin-text-field>
                <vaadin-select label="Kind" .value=${this._kind}
                    .items=${['NOTE', 'ARTICLE'].map(v => ({ label: v, value: v }))}
                    @value-changed=${e => { this._kind = e.detail.value; }}></vaadin-select>
                <vaadin-select label="Visibility" .value=${this._visibility}
                    .items=${['PUBLIC', 'UNLISTED', 'FOLLOWERS'].map(v => ({ label: v, value: v }))}
                    @value-changed=${e => { this._visibility = e.detail.value; }}></vaadin-select>
            </div>
            <vaadin-text-area label="Text" .value=${this._text}
                @value-changed=${e => { this._text = e.detail.value; }}></vaadin-text-area>
            <div class="row">
                <vaadin-button theme="primary" ?disabled=${this._sending || !this._account || !this._text}
                    @click=${this._publish}>Publish</vaadin-button>
                <span class="muted">Publishes for real, for the account, to every linked network. An ARTICLE uses the first line as its title.</span>
            </div>
            ${this._outcome ? this._renderOutcome(this._outcome) : ''}`;
    }

    _publish() {
        this._sending = true;
        this._outcome = null;
        this.jsonRpc.publishTestPost({ account: this._account, text: this._text, kind: this._kind, visibility: this._visibility })
            .then(r => { this._outcome = r.result; this._load(); })
            .catch(e => { this._outcome = { error: (e && e.error && e.error.message) || 'publishTestPost() failed' }; })
            .finally(() => { this._sending = false; });
    }

    _renderOutcome(o) {
        if (o.error) {
            return html`<span class="error">${o.error}</span>`;
        }
        return html`
            <table>
                <thead><tr><th>Network</th><th>Result</th><th>Detail</th></tr></thead>
                <tbody>${Object.entries(o.results).map(([n, r]) => html`
                    <tr><td>${n}</td><td>${r.outcome}</td><td>${this._detail(r)}</td></tr>`)}
                </tbody>
            </table>`;
    }

    _detail(r) {
        if (r.outcome === 'published') {
            return [r.url, r.note].filter(x => x).join(' - ');
        }
        if (r.outcome === 'skipped') {
            return r.reason + ': ' + r.detail;
        }
        return r.code + ': ' + r.reason + (r.retryable ? ' (retryable)' : '');
    }

    _renderRecent() {
        const posts = this._recent;
        if (!posts) {
            return html`<span>Loading...</span>`;
        }
        if (posts.length === 0) {
            return html`<span class="muted">Nothing published yet.</span>`;
        }
        return html`
            <table>
                <thead><tr><th>Post</th><th>Account</th><th>Kind / visibility</th><th>Per network</th></tr></thead>
                <tbody>${posts.map(p => html`
                    <tr><td><code>${p.postId}</code><br><span class="muted">${p.excerpt}</span></td>
                        <td>${p.account}</td>
                        <td>${p.kind} / ${p.visibility}</td>
                        <td>${p.networks.map(s => html`<div>${s.network}: <b>${s.status}</b>${s.code ? ' ' + s.code : ''}${this._delivery(s)}
                            ${s.url ? html` <a href=${s.url} target="_blank" rel="noopener">link</a>` : ''}
                            ${s.detail ? html`<br><span class="muted">${s.detail}</span>` : ''}</div>`)}</td></tr>`)}
                </tbody>
            </table>`;
    }

    _delivery(s) {
        const d = s.delivery;
        return d ? ` (delivered ${d.delivered}, failed ${d.failed}, pending ${d.pending})` : '';
    }
}
customElements.define('qwc-social', QwcSocial);
