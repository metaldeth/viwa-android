//======================================================================
//================ FUNCTIONS FILE ====================================== 
//======================================================================

//======================================================================

//Structure of Errors in EEPROM
/*
#pragma warn-
eeprom struct eeprom_data
{
  unsigned  char date_mem,month_mem,year_mem,hr_mem,min_mem,error_mem,beverage_mem;
} data_st[i_mem];
#pragma warn+
 */
//==============================================
/*
void Write_Data_Mem(void)
{
   Read_Date_Time();
   #asm("cli")  
   data_st[i_counter].date_mem=date;
   data_st[i_counter].month_mem=month;
   data_st[i_counter].year_mem=year;
   data_st[i_counter].hr_mem=hr;
   data_st[i_counter].min_mem=minute;
   data_st[i_counter].error_mem=Error;
   data_st[i_counter].beverage_mem=current_container1; 
   #asm("sei")  
 }
*/
//======================================================
//void Erase_Data_Mem(void)  // Erase memory
//{
  /*
   char eeprom *edata;
   unsigned char t;
 
     edata= (char eeprom *)&data_st[0];
      for(t=0; t < (i_mem * sizeof(data_st[0])); t++)
         {
          #asm("wdr")
          *edata++ =0xFF;
         }
   */
 
  //  i_counter=0;
  //  i_counter_copy=0;
//}
//=====================================================================
/*
void Transmit_Data_Mem(void)
{
 unsigned char mem_count;
 for(mem_count=1;mem_count<=i_counter;mem_count++)
    {
     putchar3(data_st[mem_count].date_mem);  //  byte of data
     putchar3(data_st[mem_count].month_mem);  //  byte of data
     putchar3(data_st[mem_count].year_mem);  //  byte of data
     putchar3(data_st[mem_count].hr_mem);  //  byte of data
     putchar3(data_st[mem_count].min_mem);  //  byte of data
     putchar3(data_st[mem_count].error_mem);  //  byte of data
     putchar3(data_st[mem_count].beverage_mem);  //  byte of data
    }
}
*/
//==========================================================
// Thermistor resistance and ADC calculation.
//
// Rntc = R0 * exp B(1/T - 1/T0)
//
// R0 : Zero resistance @ 25 degree celsius.
// B : constant value (see datasheet)
// T0 : Zero temparature in Kevin
//
// constant from datasheet NTC Termistor type: B57861S0103+040: R0 = 10kOhm, B = 3988K, T0 = 25+273.15
// 
//            Rntc
// Vref o-----/\/\/-------
//                        |------o Vout
// 0V   o-----/\/\/-------
//            Rout
// Vref=2.5V 
// Rout = 10k
// Vout = (Vref * Rout) / (Rout + Rntc)
// ADC = (Vout / Vref) * 1024.0
//
// 2.5V is Internal Vref
//

// below table are ADC values, calculate from T=-20 to T=100
flash unsigned int temp_list[120] = 
{
86,91,97,102,108,114,120,127,133,140,                 //-20*

147,155,162,170,178,187,195,204,213,223,              //-10*C
232, 242, 252, 262, 272, 283, 293, 304, 315, 326,     //0*C
337, 349, 360, 372, 383, 395, 406, 418, 430, 442,
453, 465, 477, 489, 500, 512, 523, 534, 546, 557,
568, 579, 589, 600, 611, 621, 631, 641, 651, 661,
670, 679, 689, 798, 706, 715, 723, 732, 740, 747,
755, 763, 770, 777, 784, 791, 797, 804, 810, 816,
822, 828, 833, 839, 844, 849, 854, 859, 863, 868,
872, 877, 881, 885, 893, 896, 900, 903, 907, 910,
913, 916, 919, 922, 925, 928, 930, 933, 935, 938,
940, 942, 945, 947, 949, 951, 953, 955, 957, 958,     //100*C
};      

//=====================================================================
void Send_Themp_to_PC(unsigned char themp0,unsigned char themp1)
{
 putchar3(0xD5);   // identifier
 putchar3(0x06);  //  6 byte of data
 putchar3(0x76);   // comand 76-> send to PC massege
 putchar3(themp0);
 putchar3(themp1);
 putchar3(0x00);
 putchar3(themp0);
 putchar3(themp1);
}

//=====================================================================
void End_Of_Prepare_Beverage(void)
{
 putchar3(0xD5);   // identifier
 putchar3(0x06);  //  6 byte of data
 putchar3(0x51);   // comand 51-> return massege  End_Of_Prepare_Beverage
 putchar3(0x00);
 putchar3(0x00);
 putchar3(0x00);
 putchar3(0x00);
 putchar3(0x00);
 
 //putchar3(EOT);   // symbol "/n"
}
//=====================================================================
void Start_Of_Prepare_Beverage(void)
{
 putchar3(0xD5);   // identifier
 putchar3(0x06);  //  6 byte of data
 putchar3(0x54);   // comand 54-> return massege  Start_Of_Prepare_Beverage
 putchar3(0x00);
 putchar3(0x00);
 putchar3(0x00);
 putchar3(0x00);
 putchar3(0x00); 

 //putchar3(EOT);   //
}

//=====================================================================
void Error_Of_Prepare_Beverage(unsigned char ERROR)
{
 putchar3(0xD5);   // identifier
 putchar3(0x06);  //  6 byte of data
 putchar3(0x52);   // comand 52-> return massege  Error_Of_Prepare_Beverage
 putchar3(ERROR);
 putchar3(ERROR);
 putchar3(ERROR);
 putchar3(ERROR);
 putchar3(ERROR);

// putchar3(EOT);   //

}

//=====================================================================
void P_Machine_Mode_Set(unsigned char p_m_mode)
{
 putchar3(0xD5);   // identifier
 putchar3(0x06);  //  6 byte of data
 putchar3(0x55);   // comand 55-> return massege  Proteine machine Mode Set
 putchar3(p_m_mode);
 putchar3(p_m_mode);
 putchar3(p_m_mode);
 putchar3(p_m_mode);
 putchar3(p_m_mode);

 //putchar3(EOT);   //
}
//=====================================================================

void Water_Pump_Valve(unsigned char status)   // on pump/valve -> num=1;  off_pump/valve -> num=0
{
 if(status==0) PORTH&=~(1<<PORTH3);  // set 0 WTR_PUMP_VALVE=0;
 if(status==1) PORTH|=(1<<PORTH3);   // set 1 WTR_PUMP_VALVE=1;   
}

//=====================================================================
// Syrup Version
void Peristalic_Pump_On_Off(unsigned char num_p)   // SYRUP ONLY
{
  // Адресация перистальтических насосов  
  if(num_p==0) // All peristalic pump Off
    {
     PORTA=0x00;
     PORTJ=0x00;
    } 
    
  if(num_p==1) PRST_PUMP1=1;  // 1 syrup
  if(num_p==2) PRST_PUMP2=1;  // 2 syrup
  if(num_p==3) PRST_PUMP3=1;  // 3 syrup
  if(num_p==4) PRST_PUMP4=1;  // 4 syrup 
  if(num_p==5) PRST_PUMP5=1;  // 5 syrup 
  if(num_p==6) PRST_PUMP6=1;  // 6 syrup 
  if(num_p==7) PRST_PUMP7=1;  // 7 syrup
  if(num_p==8) PRST_PUMP8=1;  // 8 syrup
  if(num_p==9) PORTJ|=(1<<PORTJ2);   // 9 syrup 
  if(num_p==10) PORTJ|=(1<<PORTJ3);  // 10 syrup 
  if(num_p==11) PORTJ|=(1<<PORTJ4);  // 11 syrup 
  if(num_p==12) PORTJ|=(1<<PORTJ5);  // 12 syrup  
}
//=====================================================================
void Set_Color(void)
{
 // Max intensity color value - 255.
 OCR3AH=0x00;
 OCR3AL=LED_R;  // R
 OCR3BH=0x00;
 OCR3BL=LED_G;  // G
 OCR3CH=0x00;
 OCR3CL=LED_B;  // B
} 
//=====================================================================
 void Trash_WS(unsigned char trash_sensor)
 {
  putchar3(0xD5);   // identifier
  putchar3(0x06);  //  6 byte of data
  putchar3(0x77);   // comand 0x77-> send to PC massege
  putchar3(trash_sensor); 
  putchar3(trash_sensor); 
  putchar3(trash_sensor); 
  putchar3(trash_sensor); 
  putchar3(trash_sensor); 
 }
//=====================================================================

void Klap_Data(unsigned char klap_num)
{  
 if(klap_num==0)    // all off  
   {
    PORTH&=~(1<<PORTH0);  // set 0    WTR_FILT_VALVE  PORTH.0 
     PORTH&=~(1<<PORTH1);  // set 0   WTR_COLD_VALVE  PORTH.1 
      PORTH&=~(1<<PORTH2);  // set 0  WTR_CARB_VALVE  PORTH.2 
   }
 
 if(klap_num==1) PORTH|=(1<<PORTH0);  // set 1 WTR_FILT_VALVE
 if(klap_num==2) PORTH|=(1<<PORTH1);  // set 1 WTR_COLD_VALVE
 if(klap_num==3) PORTH|=(1<<PORTH2);  // set 1 WTR_CARB_VALVE

}

//====================================================================
void P_Mashine_Reset(void)
{
 // Watchdog Timer Reinitialization
 // Watchdog Timer Prescaler: OSC/2k
 // Watchdog timeout action: Reset  
 
#pragma optsize-
WDTCSR=(0<<WDIF) | (0<<WDIE) | (0<<WDP3) | (1<<WDCE) | (1<<WDE) | (0<<WDP2) | (0<<WDP1) | (0<<WDP0);
WDTCSR=(0<<WDIF) | (0<<WDIE) | (0<<WDP3) | (0<<WDCE) | (1<<WDE) | (0<<WDP2) | (0<<WDP1) | (0<<WDP0);
#ifdef _OPTIMIZE_SIZE_
#pragma optsize+
#endif
while(1)
     {
     };

}
//====================================================================
void Reset_PM_Message_toPC(void)
{
 putchar3(0xD5);   // identifier
 putchar3(0x06);  //  6 byte of data
 putchar3(0x75);   // comand 30-> send to PC massege
 putchar3(0x01);
 putchar3(0x01);
 putchar3(0x01);
 putchar3(0x01);
 putchar3(0x01);

  //putchar3(EOT);   //
}
//====================================================================
void Send_UART3_ACK(void)
{
  putchar3(0xD5);   // identifier
  putchar3(0x06);  //  8 byte of data
  putchar3(0x22);   // comand 21->ACK
  putchar3(UART_ACK);
  putchar3(UART_ACK);
  putchar3(UART_ACK);
  putchar3(UART_ACK);
  putchar3(UART_ACK);

  //putchar3(EOT);   // symbol "/n"

}
//===================================================================

void Test_Message(void)   // Blocked/ Unblocked status proteine machine
{
  if(Blocked_Status==0)  // UnBlocked  machine  
    {
     putchar3(0xD5);   // identifier
     putchar3(0x06);  //  6 byte of data
     putchar3(0x11);   // comand 0x11->  Test message  
     putchar3(0);  // 
     putchar3(0);  // 
     putchar3(0);  //        
     putchar3(0);  //
     putchar3(0);  //
    }
  if(Blocked_Status==1)  // Blocked  machine  
    {
     putchar3(0xD5);   // identifier
     putchar3(0x06);  //  6 byte of data
     putchar3(0x11);   // comand 0x11->  Test message  
     putchar3(1);  // 
     putchar3(2);  // 
     putchar3(3);  //        
     putchar3(4);  //
     putchar3(5);  //
    }  
 // putchar3(EOT);   // symbol "/n"
}
//====================================================================

void Exit_Selector(void)
{
 if(Blocked_Status==0)   // UnBlocked  machine
   { 
    b_once_displ_e=0;
    prep_bevrg_stage=STOP;
  
    b_selected_pbv=0;
      Error=0;
        PORTB=code_s[22]; // display A
          mode=AUTOMATIC;
           P_Machine_Mode_Set(mode);  //Send to PC message
      
   }  
  //---------------------------------------------------- 
   
   if(Blocked_Status==1)
     {
      b_once_displ_e=0; 
      mode=BLOCKED;   // Blocked  machine 
      P_Machine_Mode_Set(mode);  //Send to PC message
     } 
        
}
//====================================================================
//====================================================================
//====================================================================
void Mors_Syrup_Routine1(void)
{
               
                        //------- Receipt calculation ----------- 
                         temp_Time_Work_Dozators1=(unsigned int)(Time_Work_Dozators1); //*MORS_KOEF);    // -> corrected coefficient =2
                         data_water_temp=(unsigned int)(Data_Water1*10);  // ml corrected!
                         
                         // koeff_time - calculated koefficient for max time prepareing  in msec
                         time_water_fill=(unsigned int)(data_water_temp*koeff_time);    // Maximal time water filling in msec
                         
                         //wtr20ml=(unsigned int)(20*Koef_Water);  // 20 ml  
                       //  water_cnt_20per=(unsigned int)(data_water_temp*Koef_Water*0.20);  // CALCULATE WATER COUNTER VALUE 20%(PULSES)
                         water_counter=(unsigned int)((data_water_temp*Koef_Water));  // CALCULATE WATER COUNTER VALUE (PULSES) 
                         //----------------------------------------   
                         
                         b_stop_water=0;
                         b_stop_syrup=0;
                         
                         water_counter_data=0;   // reset water counter data
                         timer_fault=0; 
                         timer_prepare_bevarege_p=0;  // reset timer Syrup 
                        
                        if(temp_Time_Work_Dozators1==0)     // Only water
                          {
                            if(Type_of_water==0)  {Klap_Data(1);  Water_Pump_Valve(1);} // 0-> filtered water valve   
                            if(Type_of_water==1)  {Klap_Data(2);  Water_Pump_Valve(1);} // 1-> cold water,   
                            if(Type_of_water==2)  {Klap_Data(3);  Water_Pump_Valve(1);} // 2 -> cold gas water    
                            
                            delay_ms(200);
                            
                            timer_fault=0;
                            timer_prepare_bevarege_p=0;  // reset timer Syrup
                            Peristalic_Pump_On_Off(0);   
                            prep_bevrg_stage=COOK_BVRG_WATER_MAIN;
                          }
                           else
                               {   
                                 // Enable water and peristaltic pumps together  
                                
                                    //Mixer_On_Off(0); 
                                    if(Type_of_water==0)  {Klap_Data(1);  Water_Pump_Valve(1);} // 0-> filtered water valve   
                                    if(Type_of_water==1)  {Klap_Data(2);  Water_Pump_Valve(1);} // 1-> cold water,   
                                    if(Type_of_water==2)  {Klap_Data(3);  Water_Pump_Valve(1);} // 2 -> cold gas water    
                                  
                                   if(current_container1==9)  Peristalic_Pump_On_Off(1);   
                                   if(current_container1==10) Peristalic_Pump_On_Off(2);   
                                   if(current_container1==11) Peristalic_Pump_On_Off(3);   
                                   if(current_container1==12) Peristalic_Pump_On_Off(4); 
                                   if(current_container1==13) Peristalic_Pump_On_Off(5);
                                   if(current_container1==14) Peristalic_Pump_On_Off(6);
                                   if(current_container1==15) Peristalic_Pump_On_Off(7);   
                                   if(current_container1==16) Peristalic_Pump_On_Off(8);   
                                   if(current_container1==17) Peristalic_Pump_On_Off(9);   
                                   if(current_container1==18) Peristalic_Pump_On_Off(10); 
                                   if(current_container1==19) Peristalic_Pump_On_Off(11);
                                   if(current_container1==20) Peristalic_Pump_On_Off(12);
                          
                                    //------------------------------------------- 
                        
                                   if(time_water_fill>=temp_Time_Work_Dozators1) 
                                     {
                                      prep_bevrg_stage=COOK_BVRG_NPT_MAIN;  // Смешивание сиропа с водой
                                     }
                                       
                                   if(time_water_fill<temp_Time_Work_Dozators1)
                                     {
                                      delta_time_cook=temp_Time_Work_Dozators1-time_water_fill;  
                                      prep_bevrg_stage=COOK_BVRG_MERGE;  // Смешивание сиропа с водой  с паузой по проливу воды
                                     }  
                                  
                               }


}

//====================================================================

void Mors_Syrup_Routine2(void)
{
   
                        //------- Receipt calculation ----------- 
                         temp_Time_Work_Dozators2=(unsigned int)(Time_Work_Dozators2); //*MORS_KOEF);    // -> corrected coefficient =2
                         data_water_temp=(unsigned int)(Data_Water2*10);  // ml corrected!
                         
                         // koeff_time - calculated koefficient for max time prepareing  in msec
                         time_water_fill=(unsigned int)(data_water_temp*koeff_time);    // Maximal time water filling in msec
                         
                         //water_cnt_20per=(unsigned int)(data_water_temp*Koef_Water*0.20);  // CALCULATE WATER COUNTER VALUE 20%(PULSES)
                         water_counter=(unsigned int)((data_water_temp*Koef_Water));  // CALCULATE WATER COUNTER VALUE (PULSES) 
                         
                         b_stop_water=0;
                         b_stop_syrup=0;
                         
                         water_counter_data=0;   // reset water counter data
                         timer_fault=0; 
                         timer_prepare_bevarege_p=0;  // reset timer Syrup 
                                                
                        if(temp_Time_Work_Dozators2==0)     // Only water
                          {
                             //Mixer_On_Off(0);
                           if(Type_of_water==0)  {Klap_Data(1);  Water_Pump_Valve(1);} // 0-> filtered water valve   
                           if(Type_of_water==1)  {Klap_Data(2);  Water_Pump_Valve(1);} // 1-> cold water,   
                           if(Type_of_water==2)  {Klap_Data(3);  Water_Pump_Valve(1);} // 2 -> cold gas water    
                          
                           delay_ms(200);
                          
                           timer_fault=0;
                           timer_prepare_bevarege_p=0;  // reset timer Syrup
                           Peristalic_Pump_On_Off(0);   
                        
                           prep_bevrg_stage=COOK_BVRG_WATER_MAIN;
                          }
                           else
                               {   
                                 // Enable water and peristaltic pumps together  
                                
                                
                                    // type of water 0->water from bottle, 1->cold water, 2 ->cold gas water    water valves
                                   if(Type_of_water==0)  {Klap_Data(1);  Water_Pump_Valve(1);} // 0-> filtered water valve   
                                   if(Type_of_water==1)  {Klap_Data(2);  Water_Pump_Valve(1);} // 1-> cold water,   
                                   if(Type_of_water==2)  {Klap_Data(3);  Water_Pump_Valve(1);} // 2 -> cold gas water    
                                 
                                   if(current_container2==9) Peristalic_Pump_On_Off(1);   
                                   if(current_container2==10) Peristalic_Pump_On_Off(2);   
                                   if(current_container2==11) Peristalic_Pump_On_Off(3);   
                                   if(current_container2==12) Peristalic_Pump_On_Off(4); 
                                   if(current_container2==13) Peristalic_Pump_On_Off(5);
                                   if(current_container2==14) Peristalic_Pump_On_Off(6);
                                   if(current_container2==15) Peristalic_Pump_On_Off(7);   
                                   if(current_container2==16) Peristalic_Pump_On_Off(8);   
                                   if(current_container2==17) Peristalic_Pump_On_Off(9);   
                                   if(current_container2==18) Peristalic_Pump_On_Off(10); 
                                   if(current_container2==19) Peristalic_Pump_On_Off(11);
                                   if(current_container2==20) Peristalic_Pump_On_Off(12);  
                                 
                                    //------------------------------------------- 
                        
                                   if(time_water_fill>=temp_Time_Work_Dozators2) 
                                     {
                                      prep_bevrg_stage=COOK_BVRG_NPT_ADD;  // Смешивание сиропа с водой
                                     }
                                       
                                   if(time_water_fill<temp_Time_Work_Dozators2)
                                     {
                                      delta_time_cook=temp_Time_Work_Dozators2-time_water_fill;  
                                      prep_bevrg_stage=COOK_BVRG_MERGE;  // Смешивание сиропа с водой  с паузой по проливу воды
                                     }  
                                  
                               }

}
//====================================================================

//==================== End of File ===================================
