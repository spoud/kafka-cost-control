import {Injectable} from '@angular/core';

@Injectable({
    providedIn: 'root'
})
export class AdditionalHeadersService {

    private _headers: { [key: string]: string } = {};

    public setHeader(key: string, value: string): void {
        this._headers[key] = value;
    }

    public getHeaders(): { [key: string]: string } {
        return this._headers;
    }

    public removeHeader(key: string) {
        delete this._headers[key];
    }
}
