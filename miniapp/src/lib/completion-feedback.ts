const messages=['Одним делом меньше!','Отлично, дело закрыто!','Готово, можно выдохнуть!'];
export const completionFeedback=(id?:string)=>messages[id?Number.parseInt(id.slice(-1),16)%messages.length||0:0];
