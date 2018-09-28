import { RouterState } from "react-router-redux";
import { routerReducer } from "react-router-redux";
import {combineReducers, Reducer} from "redux";
import {reducer as reduxFormReducer} from "redux-form";
import appConfigReducer from "./app/reducer";
import { IApplicationConfigState } from "./app/types";
import loadingProgressReducer from "./loading-progress/reducer";
import { ILoadingProgressState } from "./loading-progress/types";
import localizationReducer from "./localization/reducer";
import { ILocalizationState } from "./localization/types";
import snackbarReducer from "./snackbar/reducer";
import { ISnackbarMessagesState } from "./snackbar/types";

export interface IApplicationState {
    appConfig: IApplicationConfigState;
    form: any;
    localization: ILocalizationState;
    request: ILoadingProgressState;
    routing: RouterState;
    snackbar: ISnackbarMessagesState;
}

export const reducers: Reducer<IApplicationState> = combineReducers<IApplicationState>({
    appConfig: appConfigReducer,
    form: reduxFormReducer,
    localization: localizationReducer,
    request: loadingProgressReducer,
    routing: routerReducer,
    snackbar: snackbarReducer,
});
