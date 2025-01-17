import { Injectable } from '@angular/core';
import { Observable } from 'rxjs/internal/Observable';
import { BehaviorSubject } from 'rxjs/internal/BehaviorSubject';

import { ILogger } from '../../models/logger.model';
import { ChatMessage } from '../../models/chat.model';
import { INotificationOptions } from '../../models/notification-options.model';

import { ActionService } from '../action/action.service';
import { WebrtcService } from '../webrtc/webrtc.service';
import { LoggerService } from '../logger/logger.service';
import { Signal } from '../../models/signal.model';
import { SidenavMenuService } from '../sidenav-menu/sidenav-menu.service';
import { ParticipantService } from '../participant/participant.service';
import { MenuType } from '../../models/menu.model';

@Injectable({
	providedIn: 'root'
})
export class ChatService {
	messagesObs: Observable<ChatMessage[]>;

	protected _messageList = <BehaviorSubject<ChatMessage[]>>new BehaviorSubject([]);
	protected messageList: ChatMessage[] = [];
	protected log: ILogger;
	constructor(
		protected loggerSrv: LoggerService,
		protected webrtcService: WebrtcService,
		protected participantService: ParticipantService,
		protected menuService: SidenavMenuService,
		protected actionService: ActionService
	) {
		this.log = this.loggerSrv.get('ChatService');
		this.messagesObs = this._messageList.asObservable();
	}

	subscribeToChat() {
		const session = this.webrtcService.getWebcamSession();
		session.on(`signal:${Signal.CHAT}`, (event: any) => {
			const connectionId = event.from.connectionId;
			const data = JSON.parse(event.data);
			const isMyOwnConnection = this.webrtcService.isMyOwnConnection(connectionId);
			this.messageList.push({
				isLocal: isMyOwnConnection,
				nickname: data.nickname,
				message: data.message
			});
			if (!this.menuService.isMenuOpened()) {
				const notificationOptions: INotificationOptions = {
					message: `${data.nickname.toUpperCase()} sent a message`,
					cssClassName: 'messageSnackbar',
					buttonActionText: 'READ'
				};
				this.launchNotification(notificationOptions);
			}
			this._messageList.next(this.messageList);
		});
	}

	sendMessage(message: string) {
		message = message.replace(/ +(?= )/g, '');
		if (message !== '' && message !== ' ') {
			const data = {
				message: message,
				nickname: this.participantService.getWebcamNickname()
			};

			this.webrtcService.sendSignal(Signal.CHAT, undefined, data);
		}
	}

	protected launchNotification(options: INotificationOptions) {
		this.actionService.launchNotification(options, this.menuService.toggleMenu.bind(this.menuService, MenuType.CHAT));
	}
}
